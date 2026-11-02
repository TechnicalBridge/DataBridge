package com.tbridge.payments.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jwt.SignedJWT;
import org.springframework.security.oauth2.core.*;
import org.springframework.security.oauth2.jwt.*;
import java.time.*;
import java.util.*;

/** Politica local de sesion; Nimbus sigue siendo el verificador criptografico. */
public final class JwtValidation {
    private JwtValidation() { }

    public static JwtDecoder secure(NimbusJwtDecoder decoder, Clock clock) {
        var mapped = MappedJwtClaimSetConverter.withDefaults(Map.of());
        decoder.setClaimSetConverter(raw -> {
            for (String key : List.of("sub", "id", "email", "role", "name", "rut")) {
                if (raw.containsKey(key) && !(raw.get(key) instanceof String)) throw invalid();
            }
            for (String key : List.of("iat", "exp")) {
                // Nimbus representa NumericDate parseado como java.util.Date.
                if (!(raw.get(key) instanceof Date)) throw invalid();
            }
            if (raw.containsKey("nbf") && !(raw.get("nbf") instanceof Date)) throw invalid();
            return mapped.convert(raw);
        });
        var timestamp = new JwtTimestampValidator(Duration.ZERO);
        timestamp.setClock(clock);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(timestamp, jwt -> {
            Map<String, Object> claims = jwt.getClaims();
            String id = (String) claims.get("id");
            if (id == null) id = jwt.getSubject();
            String email = (String) claims.get("email");
            String rut = (String) claims.get("rut");
            String role = (String) claims.get("role");
            boolean valid = text(id) && (text(email) || text(rut))
                    && role != null && Set.of("DEBTOR", "CREDITOR", "ADMIN").contains(role)
                    && jwt.getIssuedAt() != null && jwt.getExpiresAt() != null
                    && jwt.getExpiresAt().isAfter(jwt.getIssuedAt())
                    && clock.instant().isBefore(jwt.getExpiresAt());
            return valid ? OAuth2TokenValidatorResult.success() : OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Sesion invalida", null));
        }));
        return token -> {
            try {
                var signed = SignedJWT.parse(token);
                var header = signed.getHeader();
                // Solo se rechaza formato aqui. Ningun claim se usa para autenticar
                // antes de que el decoder verifique la firma. Nimbus puede convertir
                // sub numerico a texto: comprobar el JSON original evita esa coercion.
                var raw = signed.getPayload().toJSONObject();
                if (raw == null) throw invalid();
                for (String key : List.of("sub", "id", "email", "role", "name", "rut")) {
                    if (raw.containsKey(key) && !(raw.get(key) instanceof String)) throw invalid();
                }
                for (String key : List.of("iat", "exp")) {
                    if (!(raw.get(key) instanceof Number)) throw invalid();
                }
                if (raw.containsKey("nbf") && !(raw.get("nbf") instanceof Number)) throw invalid();
                if (!JWSAlgorithm.ES256.equals(header.getAlgorithm())
                        || !text(header.getKeyID())) throw invalid();
            } catch (Exception malformed) {
                throw invalid();
            }
            return decoder.decode(token);
        };
    }

    private static boolean text(String value) { return value != null && !value.isBlank(); }
    private static BadJwtException invalid() { return new BadJwtException("Token invalido o caducado"); }
}
