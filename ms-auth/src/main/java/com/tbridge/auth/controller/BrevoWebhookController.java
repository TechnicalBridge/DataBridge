package com.tbridge.auth.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.tbridge.auth.service.BrevoTelemetry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class BrevoWebhookController {
    private final BrevoTelemetry telemetry;
    private final byte[] secret;
    public BrevoWebhookController(BrevoTelemetry telemetry,
            @Value("${app.mail.brevo.webhook-secret:}") String secret) {
        this.telemetry = telemetry;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }
    @PostMapping("/api/webhooks/brevo")
    public ResponseEntity<Void> receive(@RequestHeader(value="X-Brevo-Secret", required=false) String supplied,
                                       @RequestBody JsonNode body) {
        if (secret.length < 32 || supplied == null || supplied.length() > 512
                || !MessageDigest.isEqual(secret, supplied.getBytes(StandardCharsets.UTF_8)))
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        // Webhook individual: no se habilita batch en la configuracion del proveedor.
        try {
            if (!body.isObject() || (!body.path("ts_event").isIntegralNumber() || !body.path("ts_event").canConvertToLong()))
                throw new IllegalArgumentException("Payload invalido");
            telemetry.event(body.path("message-id").asText(null), body.path("event").asText(),
                    Instant.ofEpochSecond(body.path("ts_event").longValue()));
        } catch (IllegalArgumentException | java.time.DateTimeException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Evento invalido");
        }
        return ResponseEntity.noContent().build();
    }
}
