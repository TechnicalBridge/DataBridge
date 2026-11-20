package com.tbridge.auth.service;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmsServiceTest {

    private static final String SID_PRUEBA = "cuenta-twilio-prueba";
    private static final String TOKEN_PRUEBA = "token-twilio-prueba";
    private HttpServer servidor;

    @AfterEach
    void apagar() {
        if (servidor != null) {
            servidor.stop(0);
            servidor = null;
        }
    }

    private String iniciarServidorTwilio(int estado, String respuestaJson,
                                         AtomicReference<String> authRecibida,
                                         AtomicReference<String> cuerpoRecibido,
                                         AtomicInteger pedidos) throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/2010-04-01/Accounts/" + SID_PRUEBA + "/Messages.json", intercambio -> {
            pedidos.incrementAndGet();
            authRecibida.set(intercambio.getRequestHeaders().getFirst("Authorization"));
            cuerpoRecibido.set(new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = respuestaJson.getBytes(StandardCharsets.UTF_8);
            intercambio.getResponseHeaders().add("Content-Type", "application/json");
            intercambio.sendResponseHeaders(estado, bytes.length);
            try (OutputStream salida = intercambio.getResponseBody()) {
                salida.write(bytes);
            }
        });
        servidor.start();
        return "http://127.0.0.1:" + servidor.getAddress().getPort();
    }

    private static Map<String, String> decodificarFormulario(String cuerpo) {
        Map<String, String> campos = new HashMap<>();
        if (cuerpo == null || cuerpo.isBlank()) return campos;
        for (String par : cuerpo.split("&")) {
            String[] partes = par.split("=", 2);
            String k = URLDecoder.decode(partes[0], StandardCharsets.UTF_8);
            String v = partes.length > 1 ? URLDecoder.decode(partes[1], StandardCharsets.UTF_8) : "";
            campos.put(k, v);
        }
        return campos;
    }

    @Test
    void ofuscarTelefono() {
        assertEquals("****", SmsService.ofuscarTelefono(null));
        assertEquals("****", SmsService.ofuscarTelefono(""));
        assertEquals("****", SmsService.ofuscarTelefono("123"));
        assertEquals("+569****21", SmsService.ofuscarTelefono("+56987654321"));
    }

    @Test
    void normalizarE164() {
        assertEquals("", SmsService.normalizarE164(null));
        assertEquals("", SmsService.normalizarE164("   "));
        assertEquals("+56947901054", SmsService.normalizarE164("+56 9 4790 1054"));
        assertEquals("+56947901054", SmsService.normalizarE164("+56-9-4790-1054"));
        assertEquals("+56947901054", SmsService.normalizarE164("56947901054"));
        assertEquals("+56947901054", SmsService.normalizarE164("9 4790 1054"));
    }

    @Test
    void enviarCodigoNoFalla() {
        SmsService servicio = new SmsService("mock");
        assertEquals("mock", servicio.transporteActivo());
        assertDoesNotThrow(() -> servicio.enviarCodigo("+56987654321", "ABC123", "15 minutos"));
    }

    @Test
    void twilio_envia_peticion_con_basic_auth_y_formato_e164() throws IOException {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> cuerpo = new AtomicReference<>();
        AtomicInteger pedidos = new AtomicInteger();
        String url = iniciarServidorTwilio(201, "{\"sid\":\"SM-test-123\"}", auth, cuerpo, pedidos);

        SmsService servicio = new SmsService("twilio", SID_PRUEBA, TOKEN_PRUEBA,
                "+1 555 000 1111", "", url, 5_000L);
        assertEquals("twilio", servicio.transporteActivo());
        servicio.enviarCodigo("+56 9 4790 1054", "K7M2QX", "15 minutos");

        assertEquals(1, pedidos.get());
        String esperadoAuth = "Basic " + Base64.getEncoder().encodeToString(
                (SID_PRUEBA + ":" + TOKEN_PRUEBA).getBytes(StandardCharsets.UTF_8));
        assertEquals(esperadoAuth, auth.get());

        Map<String, String> campos = decodificarFormulario(cuerpo.get());
        assertEquals("+56947901054", campos.get("To"));
        assertEquals("+15550001111", campos.get("From"));
        assertFalse(campos.containsKey("MessagingServiceSid"));
        assertTrue(campos.get("Body").contains("K7M2QX"));
        assertTrue(campos.get("Body").contains("15 minutos"));
        assertFalse(campos.get("Body").contains("http"));
    }

    @Test
    void twilio_usa_messaging_service_sid_cuando_esta_configurado() throws IOException {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> cuerpo = new AtomicReference<>();
        AtomicInteger pedidos = new AtomicInteger();
        String url = iniciarServidorTwilio(201, "{\"sid\":\"SM-test-456\"}", auth, cuerpo, pedidos);

        SmsService servicio = new SmsService("", SID_PRUEBA, TOKEN_PRUEBA,
                "", "servicio-mensajeria-prueba", url, 5_000L);
        assertEquals("twilio", servicio.transporteActivo());
        servicio.enviarCodigo("947901054", "Z9X8C7", "15 minutos");

        assertEquals(1, pedidos.get());
        Map<String, String> campos = decodificarFormulario(cuerpo.get());
        assertEquals("+56947901054", campos.get("To"));
        assertEquals("servicio-mensajeria-prueba", campos.get("MessagingServiceSid"));
        assertFalse(campos.containsKey("From"));
    }

    @Test
    void twilio_con_error_http_no_lanza_excepcion() throws IOException {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> cuerpo = new AtomicReference<>();
        AtomicInteger pedidos = new AtomicInteger();
        String url = iniciarServidorTwilio(400,
                "{\"code\":21608,\"message\":\"The number is unverified\"}", auth, cuerpo, pedidos);

        SmsService servicio = new SmsService("twilio", SID_PRUEBA, TOKEN_PRUEBA,
                "+15550001111", "", url, 5_000L);
        assertDoesNotThrow(() -> servicio.enviarCodigo("+56947901054", "ABC123", "15 minutos"));
        assertEquals(1, pedidos.get());
    }

    @Test
    void twilio_con_error_572006_reintenta_con_plantilla_fallback() throws IOException {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> cuerpo = new AtomicReference<>();
        AtomicInteger pedidos = new AtomicInteger();
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/2010-04-01/Accounts/" + SID_PRUEBA + "/Messages.json", intercambio -> {
            int n = pedidos.incrementAndGet();
            auth.set(intercambio.getRequestHeaders().getFirst("Authorization"));
            cuerpo.set(new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes;
            int estado;
            if (n == 1) {
                estado = 400;
                bytes = "{\"code\":572006,\"message\":\"Invalid template name. Trial accounts can only use predefined SMS templates.\"}".getBytes(StandardCharsets.UTF_8);
            } else {
                estado = 201;
                bytes = "{\"sid\":\"SM-fallback-ok\"}".getBytes(StandardCharsets.UTF_8);
            }
            intercambio.getResponseHeaders().add("Content-Type", "application/json");
            intercambio.sendResponseHeaders(estado, bytes.length);
            try (OutputStream salida = intercambio.getResponseBody()) {
                salida.write(bytes);
            }
        });
        servidor.start();
        String url = "http://127.0.0.1:" + servidor.getAddress().getPort();

        SmsService servicio = new SmsService("twilio", SID_PRUEBA, TOKEN_PRUEBA,
                "+15550001111", "", url, 5_000L);
        assertDoesNotThrow(() -> servicio.enviarCodigo("+56947901054", "FALLBK", "15 minutos"));
        assertEquals(2, pedidos.get());
        Map<String, String> campos = decodificarFormulario(cuerpo.get());
        assertEquals("+56947901054", campos.get("To"));
        assertEquals("sms_appointment_reminders", campos.get("Body"));
    }

    @Test
    void seleccion_de_transporte_y_validacion() {
        SmsService autoSinRemitente = new SmsService("", SID_PRUEBA, TOKEN_PRUEBA, "", "", "", 5_000L);
        assertEquals("mock", autoSinRemitente.transporteActivo());
        assertFalse(autoSinRemitente.estaDisponible());

        SmsService twilioSinRemitente = new SmsService("twilio", SID_PRUEBA, TOKEN_PRUEBA, "", "", "", 5_000L);
        assertEquals("twilio", twilioSinRemitente.transporteActivo());
        assertFalse(twilioSinRemitente.estaDisponible());
        assertDoesNotThrow(() -> twilioSinRemitente.enviarCodigo("+56947901054", "ABC123", "15 minutos"));

        SmsService twilioListo = new SmsService("twilio", SID_PRUEBA, TOKEN_PRUEBA, "+15550001111", "", "", 5_000L);
        assertTrue(twilioListo.estaDisponible());

        assertEquals("+56947901054", SmsService.normalizarE164("0947901054"));
        assertEquals("+56947901054", SmsService.normalizarE164("947901054"));
        assertEquals("+56947901054", SmsService.normalizarE164("+56 9 4790 1054"));

        assertThrows(IllegalArgumentException.class, () -> new SmsService("desconocido"));
    }
}
