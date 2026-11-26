package com.tbridge.gateway.security;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Los cupos del limitador, probados contra el gateway levantado.
 *
 * <p>El cupo estrecho protege el codigo de acceso y el envio de correos; el
 * general, todo lo demas. Agotar uno no puede cerrar el otro.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CuposGatewaySecurityTest {

    /** Se confia en el proxy local para poder simular un cliente distinto por prueba. */
    private static final String PROXY_LOCAL = "127\\.0\\.0\\.1|0:0:0:0:0:0:0:1|::1";

    private static final HttpServer upstream = servicio();

    @Autowired
    private WebTestClient http;

    private static HttpServer servicio() {
        try {
            HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            servidor.createContext("/", intercambio -> {
                byte[] respuesta = "{}".getBytes(StandardCharsets.UTF_8);
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

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) {
        String base = "http://127.0.0.1:" + upstream.getAddress().getPort();
        for (String servicio : new String[]{"auth", "debt", "payments", "ai"}) {
            registro.add("app.servicios." + servicio, () -> base);
        }
        registro.add("ratelimit.auth-capacity", () -> 2);
        registro.add("ratelimit.global-capacity", () -> 3);
        registro.add("ratelimit.auth-refill-minutes", () -> 60);
        registro.add("ratelimit.global-refill-minutes", () -> 60);
        registro.add("spring.cloud.gateway.server.webflux.trusted-proxies", () -> PROXY_LOCAL);
        registro.add("logging.level.com.tbridge.gateway", () -> "WARN");
    }

    private WebTestClient.ResponseSpec pedir(String metodo, String ruta, String ip) {
        return http.method(HttpMethod.valueOf(metodo)).uri(ruta)
                .header("X-Forwarded-For", ip)
                .exchange();
    }

    @Test
    void el_cupo_estrecho_del_acceso_responde_429_al_tercer_intento() {
        String ip = "203.0.113.10";

        for (int i = 0; i < 2; i++) {
            pedir("POST", "/api/auth/acceso", ip).expectStatus().isOk();
        }
        pedir("POST", "/api/auth/acceso", ip)
                .expectStatus().isEqualTo(429)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody().jsonPath("$.error").isEqualTo("Demasiadas solicitudes. Intenta en un minuto.");
    }

    @Test
    void el_cupo_general_responde_429_al_cuarto_intento() {
        String ip = "203.0.113.11";

        for (int i = 0; i < 3; i++) {
            pedir("GET", "/api/debts/3", ip).expectStatus().isOk();
        }
        pedir("GET", "/api/debts/3", ip)
                .expectStatus().isEqualTo(429)
                .expectBody().jsonPath("$.error").isEqualTo("Demasiadas solicitudes. Intenta en un minuto.");
    }

    @Test
    void agotar_el_cupo_del_acceso_no_cierra_el_resto_del_portal() {
        String ip = "203.0.113.12";

        for (int i = 0; i < 2; i++) {
            pedir("POST", "/api/auth/acceso", ip).expectStatus().isOk();
        }
        pedir("POST", "/api/auth/acceso", ip).expectStatus().isEqualTo(429);

        pedir("GET", "/api/debts/3", ip).expectStatus().isOk();
    }

    @Test
    void renovar_la_sesion_gasta_el_cupo_general_y_no_el_del_codigo() {
        String ip = "203.0.113.13";

        for (int i = 0; i < 3; i++) {
            pedir("POST", "/api/auth/refresh", ip).expectStatus().isOk();
        }
        pedir("POST", "/api/auth/refresh", ip).expectStatus().isEqualTo(429);

        pedir("POST", "/api/auth/acceso", ip).expectStatus().isOk();
    }

    @Test
    void las_consultas_previas_del_navegador_no_gastan_el_cupo() {
        String ip = "203.0.113.14";

        for (int i = 0; i < 10; i++) {
            pedir("OPTIONS", "/api/auth/acceso", ip).expectStatus().isOk();
        }
        pedir("POST", "/api/auth/acceso", ip).expectStatus().isOk();
    }

    @AfterAll
    static void cerrar() {
        upstream.stop(0);
    }
}
