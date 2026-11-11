package com.tbridge.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.auth.service.ResendDeliveryTelemetry;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class CorreoPorResend extends JavaMailSenderImpl {
    private static final Logger log = LoggerFactory.getLogger(CorreoPorResend.class);
    private final HttpClient http;
    private final URI url;
    private final String clave;
    private final Duration espera;
    private final ObjectMapper json;
    private final ResendDeliveryTelemetry telemetria;

    CorreoPorResend(String url, String clave, Duration espera, ObjectMapper json, ResendDeliveryTelemetry telemetria) {
        this.url = URI.create(url);
        this.clave = clave;
        this.espera = espera;
        this.json = json;
        this.telemetria = telemetria;
        this.http = HttpClient.newBuilder().connectTimeout(espera).build();
    }

    @Override
    protected void doSend(MimeMessage[] mensajes, Object[] originales) {
        for (MimeMessage mensaje : mensajes) {
            enviar(cuerpo(mensaje));
        }
    }

    private Map<String, Object> cuerpo(MimeMessage mensaje) {
        try {
            Address[] destinatarios = mensaje.getRecipients(Message.RecipientType.TO);
            if (destinatarios == null || destinatarios.length == 0) {
                throw new MailParseException("Falta el destinatario del correo");
            }
            Address[] remitentes = mensaje.getFrom();
            if (remitentes == null || remitentes.length != 1) {
                throw new MailSendException("Resend requiere un remitente (MAIL_FROM)");
            }
            String[] versiones = new String[2];
            CorreoPorBrevo.versiones(mensaje, versiones);
            Map<String, Object> cuerpo = new LinkedHashMap<>();
            cuerpo.put("from", ((InternetAddress) remitentes[0]).toUnicodeString());
            cuerpo.put("to", direcciones(destinatarios));
            cuerpo.put("subject", mensaje.getSubject());
            if (versiones[0] != null) cuerpo.put("text", versiones[0]);
            cuerpo.put("html", versiones[1] != null ? versiones[1]
                    : "<pre>" + HtmlUtils.htmlEscape(versiones[0] == null ? "" : versiones[0]) + "</pre>");
            if (mensaje.getHeader("Reply-To") != null) cuerpo.put("reply_to", direcciones(mensaje.getReplyTo()));
            return cuerpo;
        } catch (MessagingException | IOException e) {
            throw new MailSendException("No se pudo preparar el correo para Resend");
        }
    }

    private static List<String> direcciones(Address[] direcciones) {
        return Arrays.stream(direcciones).map(InternetAddress.class::cast)
                .map(InternetAddress::getAddress).toList();
    }

    private void enviar(Map<String, Object> cuerpo) {
        long inicio = System.nanoTime();
        HttpResponse<String> respuesta;
        try {
            HttpRequest pedido = HttpRequest.newBuilder(url)
                    .timeout(espera)
                    .header("Authorization", "Bearer " + clave)
                    .header("Content-Type", "application/json")
                    .header("Idempotency-Key", "mail-send/" + UUID.randomUUID())
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(cuerpo)))
                    .build();
            respuesta = http.send(pedido, HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new MailSendException("Resend no respondió a tiempo; aceptación desconocida");
        } catch (IOException e) {
            throw new MailSendException("No se pudo completar el envío a Resend");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MailSendException("Se interrumpió el envío a Resend");
        }
        int estado = respuesta.statusCode();
        if (estado / 100 == 2) {
            long duracionMs = (System.nanoTime() - inicio) / 1_000_000;
            Instant aceptado = Instant.now();
            UUID id = idAceptado(respuesta.body());
            if (id != null) {
                try {
                    telemetria.aceptar(id, aceptado);
                } catch (DataAccessException e) {
                    log.warn("No se pudo registrar la aceptación de Resend provider_message_id={}", id);
                }
            }
            log.info("mail_acceptance provider=resend event=accepted duration_ms={} provider_message_id={}",
                    duracionMs, id);
            return;
        }
        if (estado == 401 || estado == 403) {
            throw new MailAuthenticationException("Resend rechazó la credencial o los permisos (HTTP " + estado + ")");
        }
        String categoria = switch (estado) {
            case 400, 422 -> "solicitud inválida";
            case 429 -> "límite de solicitudes o cuota";
            default -> estado / 100 == 5 ? "error temporal del proveedor" : "envío rechazado";
        };
        throw new MailSendException("Resend: " + categoria + " (HTTP " + estado + ")");
    }

    private UUID idAceptado(String respuesta) {
        try {
            return UUID.fromString(json.readTree(respuesta).path("id").asText());
        } catch (IOException | IllegalArgumentException | NullPointerException e) {
            // Un 2xx ya fue aceptado. Una respuesta inesperada no debe provocar otro envío.
            return null;
        }
    }
}
