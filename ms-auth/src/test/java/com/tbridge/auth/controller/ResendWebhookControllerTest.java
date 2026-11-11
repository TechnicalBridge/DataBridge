package com.tbridge.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.svix.Webhook;
import com.tbridge.auth.service.ResendDeliveryTelemetry;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ResendWebhookControllerTest {
    @Test
    void rechaza_el_webhook_si_no_hay_secreto_configurado() throws Exception {
        var telemetria = mock(ResendDeliveryTelemetry.class);
        var mvc = MockMvcBuilders.standaloneSetup(
                new ResendWebhookController("", new ObjectMapper(), telemetria)).build();
        mvc.perform(post("/api/webhooks/resend").header("svix-id", "msg_fixture")
                .header("svix-timestamp", Instant.now().getEpochSecond())
                .header("svix-signature", "fixture").content("{}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(telemetria);
    }
    @Test
    void acepta_eventos_firmados_y_rechaza_el_cuerpo_alterado() throws Exception {
        byte[] llave = new byte[32];
        new java.security.SecureRandom().nextBytes(llave);
        String secreto = Base64.getEncoder().encodeToString(llave);
        var json = new ObjectMapper();
        var telemetria = mock(ResendDeliveryTelemetry.class);
        var mvc = MockMvcBuilders.standaloneSetup(new ResendWebhookController(secreto, json, telemetria)).build();
        var firma = new Webhook(llave);
        Instant ahora = Instant.now();
        UUID mensaje = UUID.randomUUID();
        for (String tipo : List.of("sent", "delivered", "delivery_delayed", "bounced", "failed", "suppressed", "complained")) {
            String id = "msg_" + tipo;
            String evento = "email." + tipo;
            var nodo = json.createObjectNode().put("type", evento).put("created_at", ahora.toString());
            nodo.putObject("data").put("email_id", mensaje.toString()).put("subject", "fixture");
            String cuerpo = nodo.toPrettyString();
            String signature = firma.sign(id, ahora.getEpochSecond(), cuerpo);
            var pedido = post("/api/webhooks/resend").header("svix-id", id)
                    .header("svix-timestamp", ahora.getEpochSecond()).header("svix-signature", signature);
            mvc.perform(pedido.content(cuerpo + " ")).andExpect(status().isUnauthorized());
            mvc.perform(pedido.content(cuerpo)).andExpect(status().isOk());
            verify(telemetria).registrar(eq(id), eq(mensaje), eq(evento), eq(ahora), any(Instant.class));
        }
        verifyNoMoreInteractions(telemetria);
    }
}
