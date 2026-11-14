package com.tbridge.gateway.integration;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cada ruta del gateway termina en el servicio que le toca.
 *
 * <p>Se levanta un servidor HTTP local por servicio y se mira cual recibio la
 * peticion. No hay base de datos ni servicios reales: lo que se prueba es el
 * reparto de {@link com.tbridge.gateway.config.RutasConfig} y que la peticion
 * llegue intacta.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RutasGatewayIntegrationTest {

    private record Recibido(String servicio, String metodo, String ruta, String autorizacion, String cuerpo) {
    }

    private static final List<Recibido> recibidos = new CopyOnWriteArrayList<>();

    private static final HttpServer auth = servicio("ms-auth");
    private static final HttpServer debt = servicio("ms-debt");
    private static final HttpServer payments = servicio("ms-payments");
    private static final HttpServer ai = servicio("ms-ai");

    @Autowired
    private WebTestClient http;

    private static HttpServer servicio(String nombre) {
        try {
            HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            servidor.createContext("/", intercambio -> {
                String cuerpo = new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                recibidos.add(new Recibido(
                        nombre,
                        intercambio.getRequestMethod(),
                        intercambio.getRequestURI().getPath(),
                        intercambio.getRequestHeaders().getFirst("Authorization"),
                        cuerpo));
                byte[] respuesta = ("{\"servicio\":\"" + nombre + "\"}").getBytes(StandardCharsets.UTF_8);
                intercambio.getResponseHeaders().set("Content-Type", "application/json");
                intercambio.sendResponseHeaders(200, respuesta.length);
                intercambio.getResponseBody().write(respuesta);
                intercambio.close();
            });
            servidor.start();
            return servidor;
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String base(HttpServer servidor) {
        return "http://127.0.0.1:" + servidor.getAddress().getPort();
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) {
        registro.add("app.servicios.auth", () -> base(auth));
        registro.add("app.servicios.debt", () -> base(debt));
        registro.add("app.servicios.payments", () -> base(payments));
        registro.add("app.servicios.ai", () -> base(ai));
        //  Cupos altos: esta prueba mira el reparto, no el limitador.
        registro.add("ratelimit.auth-capacity", () -> 1000);
        registro.add("ratelimit.global-capacity", () -> 1000);
    }

    @BeforeEach
    void limpiar() {
        recibidos.clear();
    }

    @Test
    void cada_ruta_llega_a_su_servicio() {
        String[][] rutas = {
                {"GET", "/api/auth/enlace", "ms-auth"},
                {"GET", "/api/me", "ms-auth"},
                {"GET", "/api/debts/3", "ms-debt"},
                {"GET", "/api/analytics/resumen", "ms-debt"},
                {"POST", "/api/claves/generar", "ms-debt"},
                {"GET", "/api/v1/carteras", "ms-debt"},
                {"POST", "/api/payments/checkout", "ms-payments"},
                {"POST", "/api/ai/chat", "ms-ai"},
                {"GET", "/api/admin/auth/estado", "ms-auth"},
                {"GET", "/api/admin/debt/estado", "ms-debt"},
                {"GET", "/api/admin/payments/estado", "ms-payments"},
        };

        for (String[] caso : rutas) {
            String metodo = caso[0];
            String ruta = caso[1];
            String servicio = caso[2];

            WebTestClient.RequestBodySpec pedido = http.method(HttpMethod.valueOf(metodo)).uri(ruta);
            if ("POST".equals(metodo)) {
                pedido.bodyValue("{}");
            }
            pedido.exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.servicio").isEqualTo(servicio);

            assertTrue(recibidos.stream().anyMatch(r -> r.servicio().equals(servicio)
                            && r.metodo().equals(metodo) && r.ruta().equals(ruta)),
                    () -> "No llego a " + servicio + ": " + metodo + " " + ruta + ". Recibido: " + recibidos);
        }
    }

    @Test
    void la_documentacion_de_cada_servicio_se_reescribe_antes_de_llegar() {
        String[] servicios = {"ms-auth", "ms-debt", "ms-payments", "ms-ai"};

        for (String servicio : servicios) {
            http.get().uri("/v3/api-docs/" + servicio + "/portal")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.servicio").isEqualTo(servicio);

            //  /v3/api-docs/ms-auth/portal -> /v3/api-docs/portal en el servicio.
            assertTrue(recibidos.stream().anyMatch(r -> r.servicio().equals(servicio)
                            && r.ruta().equals("/v3/api-docs/portal")),
                    () -> "La documentacion de " + servicio + " no se reescribio. Recibido: " + recibidos);
        }
    }

    @Test
    void el_metodo_el_cuerpo_y_la_autorizacion_llegan_intactos() {
        http.post().uri("/api/payments/webhooks/khipu")
                .header("Authorization", "Bearer ficticio")
                .bodyValue("{\"evento\":\"pago\"}")
                .exchange()
                .expectStatus().isOk();

        Recibido recibido = recibidos.getLast();
        assertEquals("ms-payments", recibido.servicio());
        assertEquals("POST", recibido.metodo());
        assertEquals("/api/payments/webhooks/khipu", recibido.ruta());
        assertEquals("Bearer ficticio", recibido.autorizacion());
        assertEquals("{\"evento\":\"pago\"}", recibido.cuerpo());
    }

    @Test
    void lo_interno_no_tiene_puerta_publica() {
        for (String ruta : new String[]{"/internal/uf", "/internal/claves", "/internal/codigos"}) {
            http.get().uri(ruta).exchange().expectStatus().isNotFound();
        }
        assertTrue(recibidos.isEmpty(), () -> "Algun servicio recibio una ruta interna: " + recibidos);
    }

    @AfterAll
    static void cerrar() {
        auth.stop(0);
        debt.stop(0);
        payments.stop(0);
        ai.stop(0);
    }
}
