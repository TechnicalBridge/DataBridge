package com.tbridge.auth.controller;

import com.tbridge.auth.config.SecurityConfig;
import com.tbridge.auth.security.JwtTestConfig;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.crypto.ECDSAVerifier;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@WebMvcTest(JwksController.class)
@Import({SecurityConfig.class,JwtTestConfig.class})
@ActiveProfiles("test")
class JwksControllerTest {
    @Autowired MockMvc mvc;
    @Test void public_serialized_jwks_has_no_private_material_and_verifies_auth() throws Exception {
        String json=mvc.perform(get("/.well-known/jwks.json").header("Authorization","Bearer invalid"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control","no-store"))
                .andExpect(jsonPath("$.keys[0].kty").value("EC"))
                .andExpect(jsonPath("$.keys[0].crv").value("P-256"))
                .andExpect(jsonPath("$.keys[0].alg").value("ES256"))
                .andExpect(jsonPath("$.keys[0].use").value("sig"))
                .andReturn().getResponse().getContentAsString();
        assertFalse(json.contains("\"d\""));
        var publicKey=JWKSet.parse(json).getKeys().getFirst().toECKey();
        assertFalse(publicKey.isPrivate());
        var token=SignedJWT.parse(JwtTestConfig.service().issue("u",null,"DEBTOR",null,"rut"));
        assertTrue(token.verify(new ECDSAVerifier(publicKey)));
    }
    @Test void no_session_required() throws Exception {
        mvc.perform(get("/.well-known/jwks.json")).andExpect(status().isOk());
    }
}
