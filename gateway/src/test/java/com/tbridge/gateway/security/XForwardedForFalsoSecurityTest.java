package com.tbridge.gateway.security;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

/**
 * Un {@code X-Forwarded-For} inventado no puede abrir un cupo nuevo.
 *
 * <p>El atacante manda un valor distinto en cada intento. Como aquí nadie es
 * proxy de confianza, la cabecera no vale y todos los intentos caen en el
 * mismo cubo: prueba a quien le habla directo al gateway.
 *
 * <p>El caso de un proxy de confianza que concatena la cabecera (#349) lo
 * cubren las pruebas de #355 y no se repite aquí.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class XForwardedForFalsoSecurityTest {

    /** Nadie es proxy de confianza: lo que diga la cabecera se ignora. */
    private static final String SIN_PROXIES = "^$";

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
        registro.add("spring.cloud.gateway.server.webflux.trusted-proxies", () -> SIN_PROXIES);
        registro.add("logging.level.com.tbridge.gateway", () -> "WARN");
    }

    @Test
    void el_cupo_del_codigo_no_se_renueva_cambiando_la_cabecera() {
        for (int i = 0; i < 10; i++) {
            var estado = http.post().uri("/api/auth/acceso")
                    .header("X-Forwarded-For", "198.51.100." + (i + 1))
                    .exchange()
                    .expectStatus();

            if (i < 2) {
                estado.isOk();
            } else {
                estado.isEqualTo(429);
            }
        }
    }

    @Test
    void el_cupo_general_tampoco_se_renueva_cambiando_la_cabecera() {
        for (int i = 0; i < 10; i++) {
            var estado = http.get().uri("/api/debts/3")
                    .header("X-Forwarded-For", "198.51.100." + (i + 1))
                    .exchange()
                    .expectStatus();

            if (i < 3) {
                estado.isOk();
            } else {
                estado.isEqualTo(429);
            }
        }
    }

    @AfterAll
    static void cerrar() {
        upstream.stop(0);
    }
}
