package com.tbridge.gateway.config;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** HTTP real hacia Gateway y un doble local de Auth: no envía correos ni usa producción. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class AccesoPorCodigoHttpTest {
    private static final String ORIGEN = "https://portal.example.com";
    private static final AtomicReference<String> RECIBIDO = new AtomicReference<>();
    private static final HttpServer AUTH = servidorAuth();
    private final HttpClient cliente = HttpClient.newHttpClient();

    @LocalServerPort
    private int puerto;

    private static HttpServer servidorAuth() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/auth/codigo", exchange -> {
                RECIBIDO.set(exchange.getRequestMethod() + " " + exchange.getRequestURI()
                        + " " + new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                byte[] respuesta = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(202, respuesta.length);
                exchange.getResponseBody().write(respuesta);
                exchange.close();
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void configurar(DynamicPropertyRegistry properties) {
        properties.add("app.servicios.auth", () -> "http://127.0.0.1:" + AUTH.getAddress().getPort());
        properties.add("CORS_ORIGINS", () -> ORIGEN);
    }

    @AfterAll
    static void cerrar() {
        AUTH.stop(0);
    }

    private HttpRequest.Builder peticion() {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + puerto + "/api/auth/codigo"));
    }

    @Test
    void el_origen_permitido_supera_preflight_y_el_payload_llega_a_auth() throws Exception {
        var preflight = cliente.send(peticion().header("Origin", ORIGEN)
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, preflight.statusCode());
        assertEquals(ORIGEN, preflight.headers().firstValue("Access-Control-Allow-Origin").orElseThrow());

        String body = "{\"correo\":\"operadora@example.com\",\"rut\":null}";
        var solicitud = cliente.send(peticion().header("Origin", ORIGEN)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(202, solicitud.statusCode());
        assertEquals("POST /api/auth/codigo " + body, RECIBIDO.get());
    }

    @Test
    void un_origen_ajeno_sigue_bloqueado() throws Exception {
        var respuesta = cliente.send(peticion().header("Origin", "https://otro.example.com")
                .header("Access-Control-Request-Method", "POST")
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(403, respuesta.statusCode());
        assertTrue(respuesta.headers().firstValue("Access-Control-Allow-Origin").isEmpty());
    }
}
