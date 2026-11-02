package com.tbridge.debt.security;

import com.tbridge.debt.config.SecurityConfig;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.*;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(JwtSecurityTest.Probe.class)
@AutoConfigureMockMvc(print=MockMvcPrint.NONE)
@Import({SecurityConfig.class,JwtTestConfig.class,JwtSecurityTest.Probe.class})
@ActiveProfiles("test")
class JwtSecurityTest {
    @Autowired MockMvc mvc;
    @Autowired JwtTestIssuer issuer;
    @RestController static class Probe {
        @GetMapping({"/api/probe","/api/admin/probe"})
        Map<String,Object> portal(@AuthenticationPrincipal JwtPrincipal principal,Authentication authentication) {
            return Map.of("id",principal.id(),"role",principal.role(),"credentialsNull",authentication.getCredentials()==null);
        }
        @GetMapping("/internal/probe")
        ResponseEntity<Void> internal(@RequestHeader(value="X-Internal-Key",required=false) String key) {
            return ResponseEntity.status("internal-test".equals(key)?200:401).build();
        }
        @GetMapping("/api/v1/probe")
        ResponseEntity<Void> api(@RequestHeader(value="Authorization",required=false) String key) {
            return ResponseEntity.status("Bearer tbk_test".equals(key)?200:401).build();
        }
        @GetMapping({"/actuator/health/probe","/v3/api-docs/probe"})
        String health() { return "ok"; }
    }
    @ParameterizedTest @ValueSource(strings={"DEBTOR","CREDITOR","ADMIN"})
    void principal_roles_and_credentials(String role) throws Exception {
        String token=issuer.issue("u","person@example.com",role,null,"rut");
        mvc.perform(get(role.equals("ADMIN")?"/api/admin/probe":"/api/probe").header("Authorization","Bearer "+token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.role").value(role))
                .andExpect(jsonPath("$.credentialsNull").value(true));
        mvc.perform(get(role.equals("ADMIN")?"/api/probe":"/api/admin/probe").header("Authorization","Bearer "+token))
                .andExpect(status().isForbidden());
    }
    @ParameterizedTest @ValueSource(strings={"HS256","HS384","HS512","RS256","none"})
    void forbidden_algorithms_are_401(String alg) throws Exception {
        String token=JwtTestTokens.token(JwtTestTokens.claims(),JwtTestTokens.PAIR,JwtTestTokens.publicKey(JwtTestTokens.PAIR).getKeyID(),alg);
        mvc.perform(get("/api/probe").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
    }
    @Test void missing_or_unknown_kid_and_wrong_key_are_401() throws Exception {
        for(String kid:Arrays.asList(null,"unknown")) {
            String token=JwtTestTokens.token(JwtTestTokens.claims(),JwtTestTokens.PAIR,kid,"ES256");
            mvc.perform(get("/api/probe").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
        }
        var other=JwtTestTokens.pair("secp256r1");
        String token=JwtTestTokens.token(JwtTestTokens.claims(),other,JwtTestTokens.publicKey(JwtTestTokens.PAIR).getKeyID(),"ES256");
        mvc.perform(get("/api/probe").header("Authorization","Bearer "+token)).andExpect(status().isUnauthorized());
    }
    @Test void api_and_internal_use_their_own_keys() throws Exception {
        mvc.perform(get("/api/v1/probe").header("Authorization","Bearer tbk_test")).andExpect(status().isOk());
        mvc.perform(get("/api/v1/probe").header("Authorization","Bearer "+JwtTestTokens.token())).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/probe").header("X-Internal-Key","internal-test").header("Authorization","Bearer not-jwt"))
                .andExpect(status().isOk());
        mvc.perform(get("/internal/probe")).andExpect(status().isUnauthorized());
    }
    @Test void absent_token_and_health_docs() throws Exception {
        mvc.perform(get("/api/probe")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health/probe")).andExpect(status().isOk());
        mvc.perform(get("/v3/api-docs/probe")).andExpect(status().isOk());
    }
    @Test void service_failure_handler_returns_503_without_details() throws Exception {
        var response=new org.springframework.mock.web.MockHttpServletResponse();
        new JwtAuthenticationFailureHandler().onAuthenticationFailure(
                new org.springframework.mock.web.MockHttpServletRequest(),response,new AuthenticationServiceException("sensitive"));
        assertEquals(503,response.getStatus()); assertFalse(response.getContentAsString().contains("sensitive"));
    }
}
