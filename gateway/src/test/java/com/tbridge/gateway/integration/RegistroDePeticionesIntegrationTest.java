package com.tbridge.gateway.integration;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El registro de peticiones del gateway anota el metodo y la ruta, sin la
 * consulta: ahi pueden viajar tokens o datos personales.
 *
 * <p>Se prueba contra el gateway levantado, no contra el filtro aislado, para
 * comprobar que el filtro queda enganchado en la cadena de verdad.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ExtendWith(OutputCaptureExtension.class)
class RegistroDePeticionesIntegrationTest {

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
    }

    @Test
    void anota_el_metodo_y_la_ruta_de_la_peticion(CapturedOutput salida) {
        http.get().uri("/api/debts/3").exchange().expectStatus().isOk();

        assertTrue(salida.getOut().contains("GET /api/debts/3"),
                () -> "Falto el registro de la peticion. Salida: " + salida.getOut());
    }

    @Test
    void no_anota_los_parametros_de_la_consulta(CapturedOutput salida) {
        http.get().uri("/api/debts/3?token=secreto-que-no-debe-quedar")
                .exchange().expectStatus().isOk();

        assertTrue(salida.getOut().contains("GET /api/debts/3"));
        assertFalse(salida.getOut().contains("secreto-que-no-debe-quedar"),
                () -> "El registro filtro un parametro de la consulta. Salida: " + salida.getOut());
    }

    @AfterAll
    static void cerrar() {
        upstream.stop(0);
    }
}
