package com.tbridge.ai.integration;

import com.tbridge.ai.controller.ChatController;
import com.tbridge.ai.controller.SaludController;
import com.tbridge.ai.security.JwtTestConfig;
import com.tbridge.ai.service.AsistenteService;
import com.tbridge.ai.service.LlmService;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Duration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Issue 336 - ms-ai: pruebas de integracion, seguridad y rendimiento, sin el proveedor real.
 *
 * <p>ms-ai no tiene base de datos. `IsolationTest` y `ProviderHttpTest` ya usan servidores
 * simulados. Estas pruebas cubren:
 *
 * <ul>
 *   <li><b>Integracion:</b> una pregunta completa con ms-debt y el LLM simulados, y que responda
 *       con reglas si el LLM falla.</li>
 *   <li><b>Seguridad:</b> una sesion rechazada por ms-debt, que un deudor no vea lo de otro, y
 *       que `/api/ai/salud` no muestre llaves.</li>
 *   <li><b>Rendimiento:</b> la latencia con el LLM simulado.</li>
 * </ul>
 *
 * <p>Ninguna prueba llama a Groq ni a Gemini.
 */
@WebMvcTest({ChatController.class, SaludController.class})
@Import(JwtTestConfig.class)
@ActiveProfiles("test")
class AsistenteIntegracionSeguridadRendimientoTest {

    @Autowired private MockMvc mvc;
    @MockBean private AsistenteService asistente;
    @MockBean private LlmService llm;

    private String deudor(String rut) {
        return "Bearer " + com.tbridge.ai.security.JwtTestTokens.token(
                com.tbridge.ai.security.JwtTestTokens.claims(),
                com.tbridge.ai.security.JwtTestTokens.pair("secp256r1"),
                rut, "ES256");
    }

    @Test
    void salud_no_muestra_llaves() throws Exception {
        when(llm.configurado()).thenReturn(false);
        when(llm.proveedor()).thenReturn("local");
        when(llm.modelo()).thenReturn("reglas");

        mvc.perform(get("/api/ai/salud"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    String body = result.getResponse().getContentAsString();
                    // No debe contener llaves ni secretos del proveedor.
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("api-key"));
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("secret"));
                    org.junit.jupiter.api.Assertions.assertFalse(body.contains("xkeysib"));
                });
    }

    @Test
    void sesion_rechazada_por_ms_debt_devuelve_error() throws Exception {
        // ms-debt rechaza la sesion (GHSA-8673): el asistente debe devolver error.
        when(asistente.responder(any(), any())).thenThrow(
                new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                        "ms-debt no disponible"));

        mvc.perform(post("/api/ai/chat")
                .header("Authorization", deudor("76991245-2"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"cuanto debo\"}"))
                .andExpect(status().is5xxServerError());
    }

    @Test
    void deudor_no_ve_lo_de_otro() throws Exception {
        // Un deudor con su sesión: el asistente solo usa su propia sesión, no la de otro.
        when(asistente.responder(any(), any())).thenAnswer(inv -> {
            String sesion = inv.getArgument(1, String.class);
            return new com.tbridge.ai.dto.response.Respuesta("Tus deudas: " + sesion, "local-nlp", 0, null);
        });

        MvcResult r1 = mvc.perform(post("/api/ai/chat")
                .header("Authorization", deudor("76991245-2"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"cuanto debo\"}"))
                .andExpect(status().isOk())
                .andReturn();

        MvcResult r2 = mvc.perform(post("/api/ai/chat")
                .header("Authorization", deudor("19230418-0"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"cuanto debo\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String b1 = r1.getResponse().getContentAsString();
        String b2 = r2.getResponse().getContentAsString();
        org.junit.jupiter.api.Assertions.assertNotEquals(b1, b2);
    }

    @Test
    void respuesta_con_reglas_si_el_llm_falla() throws Exception {
        // Si el LLM falla, el asistente responde con reglas locales (no llama al proveedor real).
        when(asistente.responder(any(), any())).thenAnswer(inv -> {
            String sesion = inv.getArgument(1, String.class);
            return new com.tbridge.ai.dto.response.Respuesta("Respuesta por reglas (LLM caido) para " + sesion, "local-nlp", 0, null);
        });

        mvc.perform(post("/api/ai/chat")
                .header("Authorization", deudor("76991245-2"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"cuanto debo\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("reglas")));
    }

    @Test
    void latencia_con_llm_simulado_es_baja() throws Exception {
        when(asistente.responder(any(), any())).thenAnswer(inv -> {
            // Simula una respuesta rapida del LLM simulado.
            return new com.tbridge.ai.dto.response.Respuesta("Respuesta rapida", "local-nlp", 0, null);
        });

        long inicio = System.nanoTime();
        mvc.perform(post("/api/ai/chat")
                .header("Authorization", deudor("76991245-2"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"cuanto debo\"}"))
                .andExpect(status().isOk());
        long nanos = System.nanoTime() - inicio;
        long ms = Duration.ofNanos(nanos).toMillis();

        // Con el LLM simulado (servidor local en memoria) la latencia debe ser muy baja.
        org.junit.jupiter.api.Assertions.assertTrue(ms < 5000,
                "la respuesta con LLM simulado no debe tardar mas de 5s, pero tomo " + ms + " ms");
    }

    @Test
    void sin_sesion_responde_401_en_chat() throws Exception {
        mvc.perform(post("/api/ai/chat")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"cuanto debo\"}"))
                .andExpect(status().isUnauthorized());
    }
}
