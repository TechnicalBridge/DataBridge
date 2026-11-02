package com.tbridge.ai.controller;

import com.tbridge.ai.dto.response.Animo;
import com.tbridge.ai.dto.response.Respuesta;
import com.tbridge.ai.service.AsistenteService;
import com.tbridge.ai.service.LlmService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Las dos rutas del asistente, con la forma de JSON que lee el portal. */
@WebMvcTest({ChatController.class, SaludController.class})
@org.springframework.context.annotation.Import({com.tbridge.ai.config.SecurityConfig.class, com.tbridge.ai.security.JwtTestConfig.class})
class ChatControllerTest {
    private final String bearer = "Bearer " + com.tbridge.ai.security.JwtTestTokens.token();

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AsistenteService asistente;

    @MockitoBean
    private LlmService llm;

    @Test
    void sin_sesion_responde_401_y_no_pregunta_nada() throws Exception {
        mvc.perform(post("/api/ai/chat").contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"hola\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("No autorizado"));
        verifyNoInteractions(asistente);
    }

    @Test
    void con_sesion_responde_con_la_forma_que_lee_el_portal() throws Exception {
        when(asistente.responder(any(), eq(bearer))).thenReturn(
                new Respuesta("Hola, soy el asistente.", "local-nlp", 2, new Animo("neutral", 0)));

        mvc.perform(post("/api/ai/chat").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hola\",\"messages\":[{\"role\":\"assistant\",\"content\":\"...\"}]}"))
                .andExpect(status().isOk())
                .andExpect(content().json("""
                        {"reply":"Hola, soy el asistente.","source":"local-nlp","debts":2,
                         "sentimiento":{"etiqueta":"neutral","intensidad":0}}""", true));
    }

    @Test
    void un_json_mal_escrito_es_400_con_el_error_que_lee_el_portal() throws Exception {
        mvc.perform(post("/api/ai/chat").header("Authorization", bearer)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").exists());
    }

    @Test
    void la_salud_dice_si_hay_llm() throws Exception {
        when(llm.configurado()).thenReturn(false);
        when(llm.proveedor()).thenReturn("local");
        when(llm.modelo()).thenReturn("");

        mvc.perform(get("/api/ai/salud"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"ok\":true,\"service\":\"ms-ai\",\"llm\":false,\"provider\":\"local\",\"model\":\"\",\"configured\":false,\"fallbackAvailable\":true}", true));
    }
}
