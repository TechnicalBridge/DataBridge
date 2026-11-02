package com.tbridge.ai.security;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.*;
import com.nimbusds.jwt.*;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.security.oauth2.jwt.*;
import java.security.*;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.time.*;
import java.util.*;

/** Material efimero por JVM, exclusivamente para pruebas; nunca se imprime. */
public final class JwtTestTokens {
    public static final KeyPair PAIR = pair("secp256r1");
    public static KeyPair pair(String curve) {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
            generator.initialize(new ECGenParameterSpec(curve));
            return generator.generateKeyPair();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static ECKey publicKey(KeyPair pair) {
        try {
            ECKey key = new ECKey.Builder(Curve.P_256, (ECPublicKey) pair.getPublic()).build();
            return new ECKey.Builder(key).keyID(key.computeThumbprint().toString())
                    .keyUse(KeyUse.SIGNATURE).algorithm(JWSAlgorithm.ES256).build();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static Map<String, Object> claims() {
        Instant now = Instant.now();
        Map<String, Object> claims = new HashMap<>();
        claims.put("sub", "16482337-7");
        claims.put("id", "16482337-7");
        claims.put("rut", "16482337-7");
        claims.put("role", "DEBTOR");
        claims.put("iat", Date.from(now));
        claims.put("exp", Date.from(now.plusSeconds(900)));
        return claims;
    }
    public static String token(Map<String, Object> claims, KeyPair pair, String kid, String algorithm) {
        try {
            var body = new JWTClaimsSet.Builder();
            claims.forEach(body::claim);
            if ("none".equals(algorithm)) return new PlainJWT(body.build()).serialize();
            var header = new JWSHeader.Builder(JWSAlgorithm.parse(algorithm)).keyID(kid).build();
            var jwt = new SignedJWT(header, body.build());
            if (algorithm.startsWith("HS")) jwt.sign(new MACSigner(new byte[64]));
            else if ("RS256".equals(algorithm)) {
                KeyPairGenerator rsa = KeyPairGenerator.getInstance("RSA"); rsa.initialize(2048);
                jwt.sign(new RSASSASigner(rsa.generateKeyPair().getPrivate()));
            } else jwt.sign(new ECDSASigner((ECPrivateKey) pair.getPrivate()));
            return jwt.serialize();
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static String token() { return token(claims(), PAIR, publicKey(PAIR).getKeyID(), "ES256"); }
    public static NimbusJwtDecoder localDecoder(List<ECKey> keys) {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.ES256,
                new ImmutableJWKSet<>(new JWKSet(new ArrayList<JWK>(keys)))));
        processor.setJWTClaimsSetVerifier((claims, context) -> { });
        return new NimbusJwtDecoder(processor);
    }
}
