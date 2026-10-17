package com.tbridge.debt.client;

import com.sun.net.httpserver.HttpServer;
import com.tbridge.debt.exception.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lo que ms-debt entiende de cada respuesta de ms-auth, con un servidor HTTP de verdad. */
class AuthClientTest {

    private HttpServer servidor;

    /** Un ms-auth de mentira que responde siempre lo mismo. */
    private AuthClient respondiendo(int codigo, String cuerpo) throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/internal/codigos", intercambio -> {
            byte[] bytes = cuerpo.getBytes(StandardCharsets.UTF_8);
            intercambio.getResponseHeaders().add("Content-Type", "application/json");
            intercambio.sendResponseHeaders(codigo, bytes.length);
            try (OutputStream salida = intercambio.getResponseBody()) {
                salida.write(bytes);
            }
        });
        servidor.start();
        return new AuthClient("http://127.0.0.1:" + servidor.getAddress().getPort(), "clave-interna");
    }

    @AfterEach
    void apagar() {
        if (servidor != null) {
            servidor.stop(0);
        }
    }

    private static AuthClient.PedidoDeCodigo pedido() {
        return new AuthClient.PedidoDeCodigo("16482337-7", List.of("correo"), "felipe.rojas@correo.cl",
                "Patrimonio Inmuebles", "CTR-2025-014");
    }

    @Test
    void si_el_correo_sale_lee_cuando_vence_el_codigo() throws IOException {
        AuthClient auth = respondiendo(200, """
                {"codigo":"K7M2QX","rut":"16482337-7","expiraEn":"2026-10-15T13:00:00Z"}""");

        assertEquals(Instant.parse("2026-10-15T13:00:00Z"), auth.emitirCodigo(pedido()).expiraEn());
    }

    @Test
    void un_502_de_la_direccion_es_un_correo_que_no_salio_por_la_direccion() throws IOException {
        AuthClient auth = respondiendo(502, """
                {"error":"550 5.1.1 <felipe.rojas@correo.cl>: Recipient address rejected","causa":"direccion"}""");

        CorreoNoSalio fallo = assertThrows(CorreoNoSalio.class, () -> auth.emitirCodigo(pedido()));

        assertTrue(fallo.deLaDireccion());
        assertEquals(HttpStatus.BAD_GATEWAY, fallo.getStatus());
        assertEquals("el servidor rechazo la direccion (550 5.1.1 <fe**********@correo.cl>: Recipient address "
                + "rejected)", fallo.motivo(), "la direccion va tapada, como en todo el portal");
        assertEquals("No se pudo enviar el correo: " + fallo.motivo(), fallo.getMessage());
    }

    @Test
    void un_502_del_servidor_se_reintenta_solo() throws IOException {
        AuthClient auth = respondiendo(502, """
                {"error":"535-5.7.8 Username and Password not accepted","causa":"servidor"}""");

        CorreoNoSalio fallo = assertThrows(CorreoNoSalio.class, () -> auth.emitirCodigo(pedido()));

        assertFalse(fallo.deLaDireccion());
        assertTrue(fallo.motivo().startsWith("el servidor de correo de DataBridge no lo acepto (535-5.7.8"),
                fallo.motivo());
        assertTrue(fallo.motivo().endsWith("Se reintenta solo"));
    }

    @Test
    void un_502_sin_cuerpo_legible_igual_es_un_correo_que_no_salio() throws IOException {
        AuthClient auth = respondiendo(502, "<html>Bad Gateway</html>");

        CorreoNoSalio fallo = assertThrows(CorreoNoSalio.class, () -> auth.emitirCodigo(pedido()));

        assertFalse(fallo.deLaDireccion(), "sin saber de quien es la culpa, no se culpa a la direccion");
        assertTrue(fallo.motivo().contains("sin detalle"));
    }

    @Test
    void otro_error_de_ms_auth_no_es_un_correo_que_no_salio() throws IOException {
        AuthClient auth = respondiendo(500, "{\"error\":\"Error interno\"}");

        ApiException error = assertThrows(ApiException.class, () -> auth.emitirCodigo(pedido()));

        assertFalse(error instanceof CorreoNoSalio);
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatus());
    }

    @Test
    void un_motivo_muy_largo_se_corta() {
        CorreoNoSalio fallo = new CorreoNoSalio("x".repeat(500), true);

        assertEquals(300, fallo.motivo().length());
        assertTrue(fallo.motivo().endsWith("..."));
    }
}
