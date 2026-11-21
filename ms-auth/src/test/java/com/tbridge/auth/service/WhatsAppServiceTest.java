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

class WhatsAppServiceTest {

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

    private String iniciarServidorEvolution(int estado, String respuestaJson,
                                            AtomicReference<String> apiKeyRecibida,
                                            AtomicReference<String> cuerpoRecibido,
                                            AtomicInteger pedidos) throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/message/sendText/databridge", intercambio -> {
            pedidos.incrementAndGet();
            apiKeyRecibida.set(intercambio.getRequestHeaders().getFirst("apikey"));
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

    private String iniciarServidorKapso(int estado, String respuestaJson,
                                        AtomicReference<String> apiKeyRecibida,
                                        AtomicReference<String> cuerpoRecibido,
                                        AtomicInteger pedidos) throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/v23.0/597907523413541/messages", intercambio -> {
            pedidos.incrementAndGet();
            apiKeyRecibida.set(intercambio.getRequestHeaders().getFirst("X-API-Key"));
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
        assertEquals("****", WhatsAppService.ofuscarTelefono(null));
        assertEquals("****", WhatsAppService.ofuscarTelefono(""));
        assertEquals("****", WhatsAppService.ofuscarTelefono("123"));
        assertEquals("+569****21", WhatsAppService.ofuscarTelefono("+56987654321"));
        assertEquals("+569****21", WhatsAppService.ofuscarTelefono("whatsapp:+56987654321"));
    }

    @Test
    void normalizarE164YWhatsApp() {
        assertEquals("", WhatsAppService.normalizarE164(null));
        assertEquals("", WhatsAppService.normalizarE164("   "));
        assertEquals("+56947901054", WhatsAppService.normalizarE164("+56 9 4790 1054"));
        assertEquals("+56947901054", WhatsAppService.normalizarE164("+56-9-4790-1054"));
        assertEquals("+56947901054", WhatsAppService.normalizarE164("56947901054"));
        assertEquals("+56947901054", WhatsAppService.normalizarE164("9 4790 1054"));
        assertEquals("whatsapp:+56947901054", WhatsAppService.normalizarDestinoWhatsApp("+56 9 4790 1054"));
        assertEquals("whatsapp:+14155238886", WhatsAppService.normalizarRemitenteWhatsApp("+14155238886"));
    }

    @Test
    void enviarCodigoNoFallaEnModoMock() {
        WhatsAppService servicio = new WhatsAppService("mock");
        assertEquals("mock", servicio.transporteActivo());
        assertTrue(servicio.estaDisponible());
        assertDoesNotThrow(() -> servicio.enviarCodigo("+56987654321", "ABC123", "15 minutos"));
    }

    @Test
    void twilio_envia_peticion_con_basic_auth_y_prefijo_whatsapp() throws IOException {
        AtomicReference<String> auth = new AtomicReference<>();
        AtomicReference<String> cuerpo = new AtomicReference<>();
        AtomicInteger pedidos = new AtomicInteger();
        String url = iniciarServidorTwilio(201, "{\"sid\":\"WA-test-123\"}", auth, cuerpo, pedidos);

        WhatsAppService servicio = new WhatsAppService(
                "twilio",
                SID_PRUEBA,
                TOKEN_PRUEBA,
                "whatsapp:+14155238886",
                "",
                url,
                "",
                "",
                "databridge",
                "",
                "597907523413541",
                "https://app.kapso.ai/api/meta",
                2_000L
        );
        assertTrue(servicio.estaDisponible());

        servicio.enviarCodigo("+56 9 8765 4321", "WA1234", "15 minutos");

        assertEquals(1, pedidos.get());
        String credencialesEsperadas = "Basic " + Base64.getEncoder().encodeToString(
                (SID_PRUEBA + ":" + TOKEN_PRUEBA).getBytes(StandardCharsets.UTF_8));
        assertEquals(credencialesEsperadas, auth.get());

        Map<String, String> formulario = decodificarFormulario(cuerpo.get());
        assertEquals("whatsapp:+56987654321", formulario.get("To"));
        assertEquals("whatsapp:+14155238886", formulario.get("From"));
        assertTrue(formulario.get("Body").contains("WA1234"));
    }

    @Test
    void evolution_envia_peticion_con_apikey_y_payload_json() throws IOException {
        AtomicReference<String> apiKey = new AtomicReference<>();
        AtomicReference<String> cuerpo = new AtomicReference<>();
        AtomicInteger pedidos = new AtomicInteger();
        String url = iniciarServidorEvolution(200, "{\"key\":{\"id\":\"EVO-123\"}}", apiKey, cuerpo, pedidos);

        WhatsAppService servicio = new WhatsAppService(
                "evolution",
                "",
                "",
                "",
                "",
                "",
                url,
                "mi-evo-api-key",
                "databridge",
                "",
                "597907523413541",
                "https://app.kapso.ai/api/meta",
                2_000L
        );
        assertEquals("evolution", servicio.transporteActivo());
        assertTrue(servicio.estaDisponible());

        servicio.enviarCodigo("+56 9 4790 1054", "EVO789", "10 minutos");

        assertEquals(1, pedidos.get());
        assertEquals("mi-evo-api-key", apiKey.get());
        assertTrue(cuerpo.get().contains("\"number\":\"56947901054\""));
        assertTrue(cuerpo.get().contains("EVO789"));
    }

    @Test
    void kapso_envia_peticion_con_x_api_key_y_payload_json() throws IOException {
        AtomicReference<String> apiKey = new AtomicReference<>();
        AtomicReference<String> cuerpo = new AtomicReference<>();
        AtomicInteger pedidos = new AtomicInteger();
        String url = iniciarServidorKapso(200, "{\"messaging_product\":\"whatsapp\"}", apiKey, cuerpo, pedidos);

        WhatsAppService servicio = new WhatsAppService(
                "kapso",
                "",
                "",
                "",
                "",
                "",
                "",
                "",
                "databridge",
                "mi-kapso-key",
                "597907523413541",
                url,
                2_000L
        );
        assertEquals("kapso", servicio.transporteActivo());
        assertTrue(servicio.estaDisponible());

        servicio.enviarCodigo("+56 9 4790 1054", "KAP123", "15 minutos");

        assertEquals(1, pedidos.get());
        assertEquals("mi-kapso-key", apiKey.get());
        assertTrue(cuerpo.get().contains("\"to\":\"+56947901054\""));
        assertTrue(cuerpo.get().contains("KAP123"));
    }

    @Test
    void transporteInvalidoLanzaExcepcion() {
        assertThrows(IllegalArgumentException.class, () -> new WhatsAppService("invalido"));
    }

    @Test
    void telefonoVacioNoRealizaLlamada() {
        WhatsAppService servicio = new WhatsAppService("mock");
        assertDoesNotThrow(() -> servicio.enviarCodigo("", "WA1234", "15 minutos"));
        assertDoesNotThrow(() -> servicio.enviarCodigo(null, "WA1234", "15 minutos"));
    }
}
