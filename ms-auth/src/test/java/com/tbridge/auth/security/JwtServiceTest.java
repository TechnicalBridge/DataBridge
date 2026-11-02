package com.tbridge.auth.security;

import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class JwtServiceTest {
    private final JwtService jwt = JwtTestConfig.service();

    @Test void roundTripClaims() throws Exception {
        String token = jwt.issue("u-1", "ana@example.com", "DEBTOR", "Ana", "16482337-7");
        var principal = jwt.toPrincipal(jwt.parse(token));
        assertEquals(new JwtPrincipal("u-1", "ana@example.com", "DEBTOR", "Ana", "16482337-7"), principal);
        assertTrue(principal.isDebtor());
        assertEquals("ES256", SignedJWT.parse(token).getHeader().getAlgorithm().getName());
        assertEquals(JwtTestConfig.ring().activeKid(), SignedJWT.parse(token).getHeader().getKeyID());
    }
    @Test void rejectsTamperedToken() {
        assertThrows(Exception.class, () -> jwt.parse(JwtTestTokens.token() + "x"));
    }
    @Test void el_token_vive_lo_que_dice_la_configuracion_y_no_una_semana() {
        Jwt claims = jwt.parse(jwt.issue("u-1", null, "DEBTOR", null, "16482337-7"));
        assertEquals(900, Duration.between(claims.getIssuedAt(), claims.getExpiresAt()).toSeconds());
    }
    @Test void configuracion_de_vida_invalida_no_arranca() {
        assertThrows(IllegalStateException.class, () -> new JwtService(JwtTestConfig.ring(), 0));
    }
    @Test void otra_vida_configurada_se_conserva() {
        var service = new JwtService(JwtTestConfig.ring(), 3);
        var claims = service.parse(service.issue("u", null, "DEBTOR", null, "rut"));
        assertEquals(180, Duration.between(claims.getIssuedAt(), claims.getExpiresAt()).toSeconds());
    }
    @Test void nulls_omitidos_y_no_aparecen_claims_nuevos() {
        var claims = jwt.parse(jwt.issue("u", null, "DEBTOR", null, "rut")).getClaims();
        assertEquals(Set.of("sub", "id", "role", "rut", "iat", "exp"), claims.keySet());
    }
    @Test void same_key_same_kid_different_key_different_kid() {
        assertEquals(JwtTestConfig.ring().activeKid(), JwtTestConfig.ring().activeKid());
        var pair = JwtTestTokens.pair("secp256r1");
        assertNotEquals(JwtTestConfig.ring().activeKid(),
                new JwtKeyRing(pair.getPrivate(), pair.getPublic(), List.of()).activeKid());
    }
    @Test void rotation_old_new_verifies_both_emits_only_active() throws Exception {
        var next = JwtTestTokens.pair("secp256r1");
        var oldPair = JwtTestTokens.PAIR;
        var oldRing = new JwtKeyRing(oldPair.getPrivate(), oldPair.getPublic(), List.of(next.getPublic()));
        var newRing = new JwtKeyRing(next.getPrivate(), next.getPublic(), List.of(oldPair.getPublic()));
        var oldService = new JwtService(oldRing, 15);
        var newService = new JwtService(newRing, 15);
        String old = oldService.issue("u", null, "DEBTOR", null, "rut");
        String fresh = newService.issue("u", null, "DEBTOR", null, "rut");
        assertNotNull(newService.parse(old)); assertNotNull(oldService.parse(fresh));
        assertEquals(newRing.activeKid(), SignedJWT.parse(fresh).getHeader().getKeyID());
        assertEquals(2, ((List<?>) newRing.publicJwks().get("keys")).size());
        var retired = new JwtService(new JwtKeyRing(next.getPrivate(), next.getPublic(), List.of()), 15);
        assertThrows(Exception.class, () -> retired.parse(old));
        assertNotNull(retired.parse(fresh));
    }
}
