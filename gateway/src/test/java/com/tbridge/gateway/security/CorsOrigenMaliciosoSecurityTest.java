package com.tbridge.gateway.security;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
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

/**
 * CORS con un origen que no es el portal.
 *
 * <p>Antes la lista era {@code *} con credenciales. Ahora solo los origenes
 * configurados reciben permiso, y se devuelve el origen exacto, nunca un
 * comodin.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CorsOrigenMaliciosoSecurityTest {

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
        registro.add("ratelimit.auth-capacity", () -> 1000);
        registro.add("ratelimit.global-capacity", () -> 1000);
        registro.add("logging.level.com.tbridge.gateway", () -> "WARN");
    }

    @Test
    void un_origen_malicioso_no_recibe_permiso_en_la_consulta_previa() {
        http.options().uri("/api/debts/3")
                .header(HttpHeaders.ORIGIN, "https://malicioso.example")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    @Test
    void un_origen_malicioso_tampoco_puede_leer_una_respuesta_real() {
        http.get().uri("/api/debts/3")
                .header(HttpHeaders.ORIGIN, "https://malicioso.example")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN);
    }

    @Test
    void el_portal_de_confianza_recibe_su_origen_exacto_y_no_un_comodin() {
        http.options().uri("/api/debts/3")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "GET")
                .exchange()
                .expectStatus().isOk()
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, "http://localhost:5173")
                .expectHeader().valueEquals(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS, "true");
    }
}
