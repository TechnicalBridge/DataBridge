package com.tbridge.gateway.security;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * La vuelta desde Webpay no puede quedar bloqueada por CORS.
 *
 * <p>El navegador del deudor vuelve desde el sitio de Transbank con el
 * {@code Origin} de Transbank. {@link com.tbridge.gateway.filter.RetornoDePasarela}
 * lo quita solo en la ruta exacta de la vuelta; en las demas rutas de pago el
 * origen ajeno se sigue rechazando.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RetornoDePasarelaSecurityTest {

    private static final String RUTA_VUELTA = "/api/payments/public/webpay/retorno";
    private static final String ORIGEN_TRANSBANK = "https://webpay3gint.transbank.cl";

    private static final List<String> origenes = new CopyOnWriteArrayList<>();
    private static final HttpServer upstream = servicio();

    @Autowired
    private WebTestClient http;

    private static HttpServer servicio() {
        try {
            HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            servidor.createContext("/", intercambio -> {
                origenes.add(intercambio.getRequestHeaders().getFirst(HttpHeaders.ORIGIN));
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
        registro.add("ratelimit.auth-capacity", () -> 1000);
        registro.add("ratelimit.global-capacity", () -> 1000);
        registro.add("logging.level.com.tbridge.gateway", () -> "WARN");
    }

    @BeforeEach
    void limpiar() {
        origenes.clear();
    }

    @Test
    void la_vuelta_desde_transbank_pasa_sin_el_origen() {
        http.post().uri(RUTA_VUELTA)
                .header(HttpHeaders.ORIGIN, ORIGEN_TRANSBANK)
                .exchange()
                .expectStatus().isOk();

        assertEquals(1, origenes.size());
        assertNull(origenes.getFirst(), "El Origin de Transbank llego hasta ms-payments");
    }

    @Test
    void el_mismo_origen_en_otra_ruta_de_pago_se_rechaza() {
        http.post().uri("/api/payments/checkout")
                .header(HttpHeaders.ORIGIN, ORIGEN_TRANSBANK)
                .exchange()
                .expectStatus().isForbidden();

        assertTrue(origenes.isEmpty(), () -> "CORS dejo pasar el origen de Transbank: " + origenes);
    }

    @Test
    void el_origen_solo_se_quita_en_la_ruta_exacta_de_la_vuelta() {
        http.post().uri(RUTA_VUELTA + "/otra")
                .header(HttpHeaders.ORIGIN, ORIGEN_TRANSBANK)
                .exchange()
                .expectStatus().isForbidden();

        assertTrue(origenes.isEmpty(), () -> "Se quito el Origin en una ruta que no era la vuelta: " + origenes);
    }

    @AfterAll
    static void cerrar() {
        upstream.stop(0);
    }
}
