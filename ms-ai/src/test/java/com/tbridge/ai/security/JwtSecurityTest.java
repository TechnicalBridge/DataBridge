package com.tbridge.ai.security;
import com.tbridge.ai.controller.*;
import com.tbridge.ai.service.*;
import com.tbridge.ai.config.SecurityConfig;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
@WebMvcTest({ChatController.class,SaludController.class})
@Import({SecurityConfig.class,JwtTestConfig.class})
class JwtSecurityTest {
 @Autowired MockMvc mvc;
 @MockitoBean AsistenteService service;
 @MockitoBean LlmService llm;
 @ParameterizedTest @ValueSource(strings={"CREDITOR","ADMIN"})
 void wrong_role(String role) throws Exception {
  var claims=JwtTestTokens.claims(); claims.put("role",role);
  String jwt=JwtTestTokens.token(claims,JwtTestTokens.PAIR,JwtTestTokens.publicKey(JwtTestTokens.PAIR).getKeyID(),"ES256");
  mvc.perform(post("/api/ai/chat").header("Authorization","Bearer "+jwt).contentType("application/json").content("{\"message\":\"hola\"}"))
    .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("No tienes permiso para esto"));
  verifyNoInteractions(service,llm);
 }
 @ParameterizedTest @ValueSource(strings={"", "Bearer invalid", "Basic invalid"})
 void invalid_credentials(String header) throws Exception {
  mvc.perform(post("/api/ai/chat").header("Authorization",header).contentType("application/json").content("{}"))
    .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").exists());
  verifyNoInteractions(service,llm);
 }
 @Test void debtor_without_rut_even_with_email() throws Exception {
  var c=JwtTestTokens.claims(); c.remove("rut"); c.put("email","ficticio@example.invalid");
  String t=JwtTestTokens.token(c,JwtTestTokens.PAIR,JwtTestTokens.publicKey(JwtTestTokens.PAIR).getKeyID(),"ES256");
  mvc.perform(post("/api/ai/chat").header("Authorization","Bearer "+t).contentType("application/json").content("{}"))
   .andExpect(status().isUnauthorized()); verifyNoInteractions(service,llm);
 }
 @Test void rejected_jwt_matrix_never_reaches_service() throws Exception {
  var variants = new java.util.ArrayList<String>();
  String kid=JwtTestTokens.publicKey(JwtTestTokens.PAIR).getKeyID();
  for(String alg:java.util.List.of("HS256","HS384","HS512","RS256","none"))
   variants.add(JwtTestTokens.token(JwtTestTokens.claims(),JwtTestTokens.PAIR,kid,alg));
  for(String missing:java.util.List.of("unknown", "")) variants.add(JwtTestTokens.token(JwtTestTokens.claims(),JwtTestTokens.PAIR,missing,"ES256"));
  var expired=JwtTestTokens.claims();expired.put("iat",java.util.Date.from(java.time.Instant.now().minusSeconds(100)));expired.put("exp",java.util.Date.from(java.time.Instant.now().minusSeconds(1)));
  variants.add(JwtTestTokens.token(expired,JwtTestTokens.PAIR,kid,"ES256"));
  var future=JwtTestTokens.claims();future.put("nbf",java.util.Date.from(java.time.Instant.now().plusSeconds(60)));
  variants.add(JwtTestTokens.token(future,JwtTestTokens.PAIR,kid,"ES256"));
  variants.add(JwtTestTokens.token(JwtTestTokens.claims(),JwtTestTokens.pair("secp256r1"),kid,"ES256"));
  for(String token:variants) mvc.perform(post("/api/ai/chat").header("Authorization","Bearer "+token).contentType("application/json").content("{}"))
   .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.error").exists());
  verifyNoInteractions(service,llm);
 }
}
