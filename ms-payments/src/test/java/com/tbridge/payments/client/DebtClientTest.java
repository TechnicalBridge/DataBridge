package com.tbridge.payments.client;

import com.sun.net.httpserver.HttpServer;
import com.tbridge.payments.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class DebtClientTest {
    @Test
    void checkout_envia_rut_y_cuotas_y_conserva_conflictos_sin_filtrar_ausencias() throws Exception {
        AtomicInteger codigo = new AtomicInteger(403);
        AtomicInteger llamadas = new AtomicInteger();
        java.util.List<String> rutas = new java.util.concurrent.CopyOnWriteArrayList<>();
        HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/", intercambio -> {
            llamadas.incrementAndGet();
            rutas.add(intercambio.getRequestURI().toString());
            byte[] cuerpo = "{\"error\":\"Conflicto de prueba\"}".getBytes(StandardCharsets.UTF_8);
            intercambio.getResponseHeaders().set("Content-Type", "application/json");
            intercambio.sendResponseHeaders(codigo.get(), cuerpo.length);
            intercambio.getResponseBody().write(cuerpo);
            intercambio.close();
        });
        servidor.start();
        try {
            DebtClient cliente = new DebtClient("http://127.0.0.1:" + servidor.getAddress().getPort(), "prueba");
            for (int status : List.of(403, 404, 409, 400, 503)) {
                codigo.set(status);
                ApiException error = assertThrows(ApiException.class,
                        () -> cliente.obtenerParaCheckout(3L, List.of(12L, 13L), "16482337-7"));
                assertEquals(HttpStatus.valueOf(status == 404 ? 403 : status), error.getStatus());
                if (status == 403 || status == 404) {
                    assertEquals("No puedes ver esta deuda", error.getMessage());
                } else if (status == 409 || status == 400) {
                    assertEquals("Conflicto de prueba", error.getMessage());
                }
            }
            assertEquals(5, llamadas.get());
            assertTrue(rutas.stream().allMatch(r -> r.equals(
                    "/internal/debts/3/checkout?debtorRut=16482337-7&installmentIds=12&installmentIds=13")));
            assertEquals(HttpStatus.FORBIDDEN, assertThrows(ApiException.class,
                    () -> cliente.obtenerParaCheckout(3L, null, " ")).getStatus());
            assertEquals(5, llamadas.get());
        } finally {
            servidor.stop(0);
        }
    }
}
