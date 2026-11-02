package com.tbridge.ai.security;
import com.tbridge.ai.controller.*;
import com.tbridge.ai.service.*;
import com.tbridge.ai.config.SecurityConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.security.oauth2.jwt.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@WebMvcTest(ChatController.class)
@Import(SecurityConfig.class)
class JwksUnavailableSecurityTest {
 @Autowired MockMvc mvc;
 @MockitoBean JwtDecoder decoder;
 @MockitoBean AsistenteService service;
 @MockitoBean LlmService llm;
 @Test void verification_outage_has_safe_503() throws Exception {
  when(decoder.decode("ficticio")).thenThrow(new JwtException("TOKEN-MARKER-SECRET"));
  mvc.perform(post("/api/ai/chat").header("Authorization","Bearer ficticio").contentType("application/json").content("{}"))
   .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.error").value("Verificacion de sesion temporalmente no disponible"));
  verifyNoInteractions(service,llm);
 }
}
