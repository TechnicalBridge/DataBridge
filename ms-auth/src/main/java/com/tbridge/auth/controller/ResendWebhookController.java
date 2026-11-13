package com.tbridge.auth.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.svix.Webhook;
import com.svix.exceptions.EmptyWebhookSecretException;
import com.svix.exceptions.WebhookVerificationException;
import com.tbridge.auth.service.ResendDeliveryTelemetry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@RestController
public class ResendWebhookController {
    private static final Set<String> EVENTOS = Set.of("email.sent", "email.delivered",
            "email.delivery_delayed", "email.bounced", "email.failed", "email.suppressed", "email.complained");
    private final Webhook firma;
    private final ObjectMapper json;
    private final ResendDeliveryTelemetry telemetria;

    public ResendWebhookController(@Value("${app.mail.resend.webhook-secret:}") String secreto,
                                   ObjectMapper json, ResendDeliveryTelemetry telemetria) {
        this.json = json;
        this.telemetria = telemetria;
        try {
            this.firma = secreto.isBlank() ? null : new Webhook(secreto.strip());
        } catch (EmptyWebhookSecretException | IllegalArgumentException e) {
            throw new IllegalArgumentException("RESEND_WEBHOOK_SECRET inválido");
        }
    }

    @PostMapping("/api/webhooks/resend")
    public ResponseEntity<Void> recibir(@RequestBody byte[] crudo, @RequestHeader("svix-id") String id,
                                        @RequestHeader("svix-timestamp") String timestamp,
                                        @RequestHeader("svix-signature") String signature) {
        if (firma == null) return ResponseEntity.notFound().build();
        if (!id.matches("[A-Za-z0-9_-]{1,128}")) return ResponseEntity.badRequest().build();
        try {
            String cuerpo = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(crudo)).toString();
            firma.verify(cuerpo, Map.of("svix-id", List.of(id), "svix-timestamp", List.of(timestamp),
                    "svix-signature", List.of(signature)));
            JsonNode evento = json.readTree(cuerpo);
            if (evento == null || !evento.isObject()) return ResponseEntity.badRequest().build();
            String tipo = evento.path("type").asText();
            if (EVENTOS.contains(tipo)) {
                UUID mensaje = UUID.fromString(evento.path("data").path("email_id").asText());
                Instant instante = Instant.parse(evento.path("created_at").asText());
                telemetria.registrar(id, mensaje, tipo, instante, Instant.now());
            }
            return ResponseEntity.ok().build();
        } catch (WebhookVerificationException e) {
            return ResponseEntity.status(401).build();
        } catch (IOException | IllegalArgumentException | DateTimeParseException e) {
            return ResponseEntity.badRequest().build();
        }
    }
}
