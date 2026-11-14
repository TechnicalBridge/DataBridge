package com.tbridge.auth.controller;
import com.tbridge.auth.config.SecurityConfig;
import com.tbridge.auth.security.JwtService;
import com.tbridge.auth.service.BrevoTelemetry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.mockito.Mockito.*;

@WebMvcTest(value=BrevoWebhookController.class,properties="app.mail.brevo.webhook-secret=fixture-webhook-secret-at-least-32-characters")
@Import(SecurityConfig.class) @ActiveProfiles("test")
class BrevoWebhookSecurityTest {
    @Autowired MockMvc mvc;
    @MockitoBean JwtService jwt;
    @MockitoBean BrevoTelemetry telemetry;
    private static final String BODY="{\"message-id\":\"fixture\",\"event\":\"delivered\",\"ts_event\":1794090000}";
    @Test void dedicated_secret_accepts_without_portal_session() throws Exception {
        mvc.perform(post("/api/webhooks/brevo").header("X-Brevo-Secret","fixture-webhook-secret-at-least-32-characters")
                .contentType("application/json").content(BODY)).andExpect(status().isNoContent());
        verify(telemetry).event(eq("fixture"),eq("delivered"),any());
        verifyNoInteractions(jwt);
    }
    @Test void missing_secret_is_denied_before_persistence() throws Exception {
        mvc.perform(post("/api/webhooks/brevo").contentType("application/json").content(BODY)).andExpect(status().isUnauthorized());
        verifyNoInteractions(telemetry);
    }
}
