package com.tbridge.gateway.security;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El firewall por dominio del contrato de integracion.
 *
 * <p>Una llave de agencia solo puede entrar desde las redes que tiene
 * autorizadas. La comprobacion es del gateway; la autenticacion de la llave
 * sigue en ms-debt.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class FirewallIntegracionesSecurityTest {

    private static final String LLAVE = UUID.randomUUID().toString();
    private static final String PROXY_LOCAL = "127\\.0\\.0\\.1|0:0:0:0:0:0:0:1|::1";

    private static final List<String> autorizaciones = new CopyOnWriteArrayList<>();
    private static final HttpServer upstream = servicio();

    @Autowired
    private WebTestClient http;

    private static HttpServer servicio() {
        try {
            HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            servidor.createContext("/", intercambio -> {
                autorizaciones.add(intercambio.getRequestHeaders().getFirst("Authorization"));
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

    /** La misma huella SHA-256 que usa el filtro para no guardar la llave en claro. */
    private static String huella(String valor) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(valor.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) {
        String base = "http://127.0.0.1:" + upstream.getAddress().getPort();
        for (String servicio : new String[]{"auth", "debt", "payments", "ai"}) {
            registro.add("app.servicios." + servicio, () -> base);
        }
        registro.add("firewall.debt.integration-cidrs",
                () -> huella(LLAVE) + "=192.0.2.0/24,2001:db8::/32");
        registro.add("spring.cloud.gateway.server.webflux.trusted-proxies", () -> PROXY_LOCAL);
        registro.add("ratelimit.auth-capacity", () -> 1000);
        registro.add("ratelimit.global-capacity", () -> 1000);
        registro.add("logging.level.com.tbridge.gateway", () -> "WARN");
    }

    @BeforeEach
    void limpiar() {
        autorizaciones.clear();
    }

    @Test
    void una_llave_configurada_desde_afuera_del_rango_recibe_403() {
        http.get().uri("/api/v1/carteras")
                .header("Authorization", "Bearer " + LLAVE)
                .header("X-Forwarded-For", "198.51.100.20")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().isEmpty();

        assertTrue(autorizaciones.isEmpty(),
                () -> "El gateway dejo pasar una llave fuera de su red: " + autorizaciones);
    }

    @Test
    void la_misma_llave_desde_el_rango_permitido_pasa() {
        for (String ip : new String[]{"192.0.2.20", "2001:db8::20"}) {
            http.get().uri("/api/v1/carteras")
                    .header("Authorization", "Bearer " + LLAVE)
                    .header("X-Forwarded-For", ip)
                    .exchange()
                    .expectStatus().isOk();
        }

        assertEquals(List.of("Bearer " + LLAVE, "Bearer " + LLAVE), autorizaciones);
    }

    @Test
    void otra_llave_o_sin_llave_no_queda_atrapada_por_el_firewall() {
        http.get().uri("/api/v1/carteras")
                .header("Authorization", "Bearer otra-llave")
                .header("X-Forwarded-For", "198.51.100.21")
                .exchange().expectStatus().isOk();

        http.get().uri("/api/v1/carteras")
                .header("X-Forwarded-For", "198.51.100.21")
                .exchange().expectStatus().isOk();
    }

    @Test
    void el_firewall_solo_mira_el_contrato_v1_y_no_las_consultas_previas() {
        for (String ruta : new String[]{"/api/debts/3", "/api/payments/webhooks/khipu", "/api/auth/refresh"}) {
            http.get().uri(ruta)
                    .header("Authorization", "Bearer " + LLAVE)
                    .header("X-Forwarded-For", "198.51.100.22")
                    .exchange().expectStatus().isOk();
        }

        http.options().uri("/api/v1/carteras")
                .header("Authorization", "Bearer " + LLAVE)
                .header("X-Forwarded-For", "198.51.100.23")
                .exchange().expectStatus().isOk();
    }

    @Test
    void lo_interno_sigue_sin_puerta_aunque_traiga_la_llave() {
        http.get().uri("/internal/uf")
                .header("Authorization", "Bearer " + LLAVE)
                .header("X-Forwarded-For", "192.0.2.30")
                .exchange()
                .expectStatus().isNotFound();

        assertTrue(autorizaciones.isEmpty());
    }

    @AfterAll
    static void cerrar() {
        upstream.stop(0);
    }
}
