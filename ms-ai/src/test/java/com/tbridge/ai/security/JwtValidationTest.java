package com.tbridge.ai.security;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.security.oauth2.jwt.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JwtValidationTest {
    private final JwtDecoder decoder = JwtValidation.secure(
            JwtTestTokens.localDecoder(List.of(JwtTestTokens.publicKey(JwtTestTokens.PAIR))), Clock.systemUTC());
    private String token(Map<String, Object> claims, String kid, String alg) {
        return JwtTestTokens.token(claims, JwtTestTokens.PAIR, kid, alg);
    }
    private String kid() { return JwtTestTokens.publicKey(JwtTestTokens.PAIR).getKeyID(); }
    @Test void valid() { assertNotNull(decoder.decode(JwtTestTokens.token())); }
    @ParameterizedTest @ValueSource(strings={"HS256","HS384","HS512","RS256","none"})
    void prohibited_algorithm(String alg) {
        assertThrows(JwtException.class, () -> decoder.decode(token(JwtTestTokens.claims(), kid(), alg)));
    }
    @Test void wrong_ec_key() {
        var other = JwtTestTokens.pair("secp256r1");
        assertThrows(JwtException.class, () -> decoder.decode(JwtTestTokens.token(JwtTestTokens.claims(), other, kid(), "ES256")));
    }
    @ParameterizedTest @NullAndEmptySource @ValueSource(strings={"unknown", " "})
    void missing_or_unknown_kid(String kid) {
        assertThrows(JwtException.class, () -> decoder.decode(token(JwtTestTokens.claims(), kid, "ES256")));
    }
    @ParameterizedTest @ValueSource(strings={"iat","exp","role"})
    void missing_required_claim(String key) {
        var claims=JwtTestTokens.claims(); claims.remove(key);
        assertThrows(JwtException.class, () -> decoder.decode(token(claims,kid(),"ES256")));
    }
    @ParameterizedTest @ValueSource(strings={"sub","id","email","role","name","rut","iat","exp","nbf"})
    void wrong_claim_type(String key) {
        var claims=JwtTestTokens.claims(); claims.put(key, key.equals("iat") || key.equals("exp") || key.equals("nbf") ? "invalid" : 123);
        assertThrows(JwtException.class, () -> decoder.decode(token(claims,kid(),"ES256")));
    }
    @Test void subject_fallback() {
        var claims=JwtTestTokens.claims(); claims.remove("id");
        assertEquals(claims.get("sub"), JwtPrincipal.fromClaims(decoder.decode(token(claims,kid(),"ES256")).getClaims()).id());
    }
    @Test void missing_identity() {
        var claims=JwtTestTokens.claims(); claims.remove("rut");
        assertThrows(JwtException.class, () -> decoder.decode(token(claims,kid(),"ES256")));
    }
    @Test void missing_id_and_sub() {
        var claims=JwtTestTokens.claims(); claims.remove("id"); claims.remove("sub");
        assertThrows(JwtException.class, () -> decoder.decode(token(claims,kid(),"ES256")));
    }
    @Test void empty_id_does_not_fallback() {
        var claims=JwtTestTokens.claims(); claims.put("id"," ");
        assertThrows(JwtException.class, () -> decoder.decode(token(claims,kid(),"ES256")));
    }
    @Test void unknown_role() {
        var claims=JwtTestTokens.claims(); claims.put("role","SUPERUSER");
        assertThrows(JwtException.class, () -> decoder.decode(token(claims,kid(),"ES256")));
    }
    @Test void admin_without_rut() {
        var claims=JwtTestTokens.claims(); claims.remove("rut"); claims.put("role","ADMIN"); claims.put("email","admin@example.com");
        assertNotNull(decoder.decode(token(claims,kid(),"ES256")));
    }
    @Test void expired_without_skew() {
        var claims=JwtTestTokens.claims(); claims.put("iat",Date.from(Instant.now().minusSeconds(901))); claims.put("exp",Date.from(Instant.now().minusSeconds(1)));
        assertThrows(JwtException.class, () -> decoder.decode(token(claims,kid(),"ES256")));
    }
    @Test void expiry_boundary_and_invalid_duration() {
        var now=Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        var boundary=JwtValidation.secure(JwtTestTokens.localDecoder(List.of(JwtTestTokens.publicKey(JwtTestTokens.PAIR))),Clock.fixed(now,ZoneOffset.UTC));
        var claims=JwtTestTokens.claims(); claims.put("iat",Date.from(now.minusSeconds(900))); claims.put("exp",Date.from(now));
        assertThrows(JwtException.class, () -> boundary.decode(token(claims,kid(),"ES256")));
        claims.put("iat",Date.from(now)); claims.put("exp",Date.from(now));
        assertThrows(JwtException.class, () -> decoder.decode(token(claims,kid(),"ES256")));
    }
    @Test void future_nbf() {
        var claims=JwtTestTokens.claims(); claims.put("nbf",Date.from(Instant.now().plusSeconds(60)));
        assertThrows(JwtException.class, () -> decoder.decode(token(claims,kid(),"ES256")));
    }
    @Test void effective_payload_and_signature_tampering() {
        String[] parts=JwtTestTokens.token().split("\\.");
        byte[] payload=Base64.getUrlDecoder().decode(parts[1]);
        String altered=new String(payload,java.nio.charset.StandardCharsets.UTF_8).replace("DEBTOR","ADMIN");
        String body=Base64.getUrlEncoder().withoutPadding().encodeToString(altered.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(JwtException.class, () -> decoder.decode(parts[0]+"."+body+"."+parts[2]));
        byte[] sig=Base64.getUrlDecoder().decode(parts[2]); sig[0]^=1;
        String badSig=Base64.getUrlEncoder().withoutPadding().encodeToString(sig);
        assertThrows(JwtException.class, () -> decoder.decode(parts[0]+"."+parts[1]+"."+badSig));
    }
}
