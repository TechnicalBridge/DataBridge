package com.tbridge.auth.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** El envio por la API de Brevo, contra un servidor HTTP de verdad que responde como Brevo. */
class CorreoPorBrevoTest {

    private static final String CLAVE = "xkeysib-clave-de-prueba-que-nunca-se-muestra";
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<String> cuerpoRecibido = new AtomicReference<>();
    private final AtomicReference<String> claveRecibida = new AtomicReference<>();
    private final AtomicInteger pedidos = new AtomicInteger();
    private HttpServer servidor;

    /** Un Brevo de mentira que responde siempre lo mismo, y anota lo que recibio. */
    private CorreoPorBrevo respondiendo(int codigo, String cuerpo) throws IOException {
        return respondiendo(codigo, cuerpo, 0, Duration.ofSeconds(5));
    }

    private CorreoPorBrevo respondiendo(int codigo, String cuerpo, long demora, Duration espera) throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/v3/smtp/email", intercambio -> {
            pedidos.incrementAndGet();
            claveRecibida.set(intercambio.getRequestHeaders().getFirst("api-key"));
            cuerpoRecibido.set(new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(demora);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] bytes = cuerpo.getBytes(StandardCharsets.UTF_8);
            intercambio.getResponseHeaders().add("Content-Type", "application/json");
            intercambio.sendResponseHeaders(codigo, bytes.length);
            try (OutputStream salida = intercambio.getResponseBody()) {
                salida.write(bytes);
            }
        });
        servidor.start();
        String url = "http://127.0.0.1:" + servidor.getAddress().getPort() + "/v3/smtp/email";
        return new CorreoPorBrevo(url, CLAVE, espera, json);
    }

    @AfterEach
    void apagar() {
        if (servidor != null) {
            servidor.stop(0);
            servidor = null;
        }
    }

    private static SimpleMailMessage mensaje(String para) {
        SimpleMailMessage mensaje = new SimpleMailMessage();
        mensaje.setFrom("DataBridge <equipo@gmail.com>");
        mensaje.setTo(para);
        mensaje.setReplyTo("contacto@databridge.cl");
        mensaje.setSubject("Tu codigo de acceso");
        mensaje.setText("Hola,\n\nTu codigo es K7M2QX <no es una etiqueta>.\n");
        return mensaje;
    }

    @Test
    void manda_lo_que_pide_brevo_con_la_clave_en_su_cabecera() throws IOException {
        respondiendo(201, "{\"messageId\":\"<202610141300.1@smtp-relay.mailin.fr>\"}").send(mensaje("felipe.rojas@correo.cl"));

        assertEquals(CLAVE, claveRecibida.get());
        JsonNode cuerpo = json.readTree(cuerpoRecibido.get());
        assertEquals("equipo@gmail.com", cuerpo.at("/sender/email").asText());
        assertEquals("DataBridge", cuerpo.at("/sender/name").asText(), "el nombre que se ve en el buzon");
        assertEquals("felipe.rojas@correo.cl", cuerpo.at("/to/0/email").asText());
        assertEquals("Tu codigo de acceso", cuerpo.get("subject").asText());
        assertEquals("Hola,\n\nTu codigo es K7M2QX <no es una etiqueta>.\n", cuerpo.get("textContent").asText());
        assertTrue(cuerpo.get("htmlContent").asText().contains("K7M2QX &lt;no es una etiqueta&gt;"),
                "el HTML que pide Brevo es el mismo texto, escapado");
        assertEquals("contacto@databridge.cl", cuerpo.at("/replyTo/email").asText());
    }

    @Test
    void si_brevo_rechaza_al_destinatario_la_culpa_es_de_la_direccion() throws IOException {
        CorreoPorBrevo brevo = respondiendo(400, "{\"code\":\"invalid_parameter\",\"message\":\"email is not valid in to\"}");

        MailParseException fallo = assertThrows(MailParseException.class, () -> brevo.send(mensaje("felipe.rojas@correo.cl")));

        assertEquals("Brevo respondio 400 (invalid_parameter): email is not valid in to", fallo.getMessage());
    }

    @Test
    void un_remitente_sin_verificar_no_es_culpa_del_destinatario() throws IOException {
        CorreoPorBrevo brevo = respondiendo(400, "{\"code\":\"invalid_parameter\",\"message\":\"Sender is not valid\"}");

        MailSendException fallo = assertThrows(MailSendException.class, () -> brevo.send(mensaje("felipe.rojas@correo.cl")));
    }

    @Test
    void una_clave_mala_o_el_cupo_agotado_son_del_servicio_y_la_clave_nunca_aparece() throws IOException {
        for (String[] caso : new String[][]{
                {"401", "{\"code\":\"unauthorized\",\"message\":\"Key not found\"}"},
                {"402", "{\"code\":\"not_enough_credits\",\"message\":\"Not enough credits\"}"},
                {"500", "<html>Internal Server Error</html>"}}) {
            CorreoPorBrevo brevo = respondiendo(Integer.parseInt(caso[0]), caso[1]);

            MailSendException fallo = assertThrows(MailSendException.class, () -> brevo.send(mensaje("felipe.rojas@correo.cl")));
            assertTrue(fallo.getMessage().startsWith("Brevo respondio " + caso[0]), fallo.getMessage());
            assertFalse(fallo.getMessage().contains(CLAVE), caso[0]);
            apagar();
        }
    }

    @Test
    void una_ip_desconocida_bloqueada_es_del_servicio_y_la_ip_no_queda_en_el_motivo() throws IOException {
        CorreoPorBrevo brevo = respondiendo(401, "{\"message\":\"We have detected you are using an unrecognised IP "
                + "address 203.0.113.45. If you performed this action make sure to add the new IP address in this link: "
                + "https://app.brevo.com/security/authorised_ips\",\"code\":\"unauthorized\"}");

        MailSendException fallo = assertThrows(MailSendException.class, () -> brevo.send(mensaje("felipe.rojas@correo.cl")));

        assertTrue(fallo.getMessage().startsWith("Brevo respondio 401 (unauthorized): We have detected you are using "
                + "an unrecognised IP address <IP>."), fallo.getMessage());
        assertFalse(fallo.getMessage().contains("203.0.113.45"));
        assertTrue(fallo.getMessage().contains("https://app.brevo.com/security/authorised_ips"), "el enlace sigue entero");
    }

    @Test
    void tampoco_queda_una_ip_de_ipv6() throws IOException {
        CorreoPorBrevo brevo = respondiendo(401, "{\"message\":\"unrecognised IP address 2001:db8:85a3::8a2e:370:7334\","
                + "\"code\":\"unauthorized\"}");

        MailSendException fallo = assertThrows(MailSendException.class, () -> brevo.send(mensaje("felipe.rojas@correo.cl")));

        assertFalse(fallo.getMessage().contains("2001:db8"), fallo.getMessage());
    }

    @Test
    void si_brevo_no_responde_a_tiempo_es_del_servicio() throws IOException {
        CorreoPorBrevo brevo = respondiendo(201, "{}", 2_000, Duration.ofMillis(300));

        MailSendException fallo = assertThrows(MailSendException.class, () -> brevo.send(mensaje("felipe.rojas@correo.cl")));

        assertTrue(fallo.getMessage().startsWith("Brevo no respondio"), fallo.getMessage());
    }

    @Test
    void un_destinatario_mal_escrito_no_llega_a_brevo_y_es_de_la_direccion() throws IOException {
        CorreoPorBrevo brevo = respondiendo(201, "{}");

        MailParseException fallo = assertThrows(MailParseException.class, () -> brevo.send(mensaje("no es un correo")));

        assertEquals(0, pedidos.get());
        assertInstanceOf(jakarta.mail.internet.AddressException.class, fallo.getCause());
    }

    @Test
    void un_remitente_mal_configurado_no_se_le_culpa_a_ningun_deudor() throws IOException {
        CorreoPorBrevo brevo = respondiendo(201, "{}");
        SimpleMailMessage mensaje = mensaje("felipe.rojas@correo.cl");
        mensaje.setFrom("no es un correo");

        MailSendException fallo = assertThrows(MailSendException.class, () -> brevo.send(mensaje));
        assertTrue(fallo.getMessage().contains("MAIL_FROM"));
        //  Con la direccion mal escrita como causa, MailService culparia al deudor.
        assertNull(fallo.getCause());
    }

    // ------------------------------------------------------------------
    //  Un correo armado, como lo arma MailService: texto y HTML
    // ------------------------------------------------------------------

    /** Como lo arma MailService: remitente con nombre, asunto con tildes y las dos versiones. */
    private static MimeMessage armado(CorreoPorBrevo brevo, String para, String responderA) throws MessagingException {
        MimeMessage mensaje = brevo.createMimeMessage();
        mensaje.setFrom(new InternetAddress("DataBridge <equipo@gmail.com>", true));
        mensaje.setRecipient(Message.RecipientType.TO, new InternetAddress(para));
        if (responderA != null) {
            mensaje.setReplyTo(InternetAddress.parse(responderA));
        }
        mensaje.setSubject("Tu código de acceso", "UTF-8");
        MimeBodyPart texto = new MimeBodyPart();
        texto.setText("Hola,\n\nTu código es K7M2QX.\n", "UTF-8");
        MimeBodyPart html = new MimeBodyPart();
        html.setText("<p>Tu código es <strong>K7M2QX</strong>.</p>", "UTF-8", "html");
        mensaje.setContent(new MimeMultipart("alternative", texto, html));
        return mensaje;
    }

    @Test
    void de_un_correo_armado_manda_su_texto_y_su_html_tal_cual() throws Exception {
        CorreoPorBrevo brevo = respondiendo(201, "{}");

        brevo.send(armado(brevo, "felipe.rojas@correo.cl", "contacto@databridge.cl"));

        JsonNode cuerpo = json.readTree(cuerpoRecibido.get());
        assertEquals("equipo@gmail.com", cuerpo.at("/sender/email").asText());
        assertEquals("DataBridge", cuerpo.at("/sender/name").asText());
        assertEquals("felipe.rojas@correo.cl", cuerpo.at("/to/0/email").asText());
        assertEquals("Tu código de acceso", cuerpo.get("subject").asText());
        assertEquals("Hola,\n\nTu código es K7M2QX.\n", cuerpo.get("textContent").asText());
        assertEquals("<p>Tu código es <strong>K7M2QX</strong>.</p>", cuerpo.get("htmlContent").asText(),
                "el HTML del correo, no el texto escapado");
        assertEquals("contacto@databridge.cl", cuerpo.at("/replyTo/email").asText());
    }

    @Test
    void sin_responder_a_no_manda_uno_aunque_jakarta_mail_devuelva_el_remitente() throws Exception {
        CorreoPorBrevo brevo = respondiendo(201, "{}");

        brevo.send(armado(brevo, "felipe.rojas@correo.cl", null));

        assertFalse(json.readTree(cuerpoRecibido.get()).has("replyTo"));
    }

    @Test
    void un_correo_armado_de_solo_texto_lleva_igual_su_html() throws Exception {
        CorreoPorBrevo brevo = respondiendo(201, "{}");
        MimeMessage mensaje = brevo.createMimeMessage();
        mensaje.setFrom(new InternetAddress("equipo@gmail.com"));
        mensaje.setRecipient(Message.RecipientType.TO, new InternetAddress("felipe.rojas@correo.cl"));
        mensaje.setSubject("Hola");
        mensaje.setText("Uno < dos", "UTF-8");

        brevo.send(mensaje);

        JsonNode cuerpo = json.readTree(cuerpoRecibido.get());
        assertEquals("Uno < dos", cuerpo.get("textContent").asText());
        assertTrue(cuerpo.get("htmlContent").asText().contains("Uno &lt; dos"));
    }

    @Test
    void un_correo_armado_con_un_remitente_malo_no_culpa_al_deudor_ni_llega_a_brevo() throws Exception {
        CorreoPorBrevo brevo = respondiendo(201, "{}");
        MimeMessage mensaje = armado(brevo, "felipe.rojas@correo.cl", null);
        mensaje.setHeader("From", "no es un correo");

        MailSendException fallo = assertThrows(MailSendException.class, () -> brevo.send(mensaje));

        assertTrue(fallo.getMessage().contains("MAIL_FROM"), fallo.getMessage());
        assertFalse(fallo.getCause() instanceof AddressException);
        assertEquals(0, pedidos.get());
    }

    @Test
    void un_correo_armado_con_un_destinatario_malo_es_de_la_direccion() throws Exception {
        CorreoPorBrevo brevo = respondiendo(201, "{}");
        MimeMessage mensaje = armado(brevo, "felipe.rojas@correo.cl", null);
        mensaje.setHeader("To", "felipe.rojas@@correo");

        assertThrows(MailParseException.class, () -> brevo.send(mensaje));
        assertEquals(0, pedidos.get());
    }
}
