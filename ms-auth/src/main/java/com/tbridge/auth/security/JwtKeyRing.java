package com.tbridge.auth.security;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.*;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import org.springframework.security.oauth2.jwt.*;
import java.security.*;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.*;

/** Una privada activa; verificacion y endpoint reciben exclusivamente publicas. */
public final class JwtKeyRing {
    private final ECKey signingKey;
    private final JWKSet publicKeys;
    public JwtKeyRing(PrivateKey privateKey, PublicKey publicKey, List<? extends PublicKey> additional) {
        try {
            if (!(privateKey instanceof ECPrivateKey ecPrivate) || !(publicKey instanceof ECPublicKey ecPublic)
                    || !Curve.P_256.equals(Curve.forECParameterSpec(ecPrivate.getParams()))) {
                throw new IllegalArgumentException();
            }
            ECKey active = publicKey(ecPublic);
            Signature check = Signature.getInstance("SHA256withECDSA");
            byte[] challenge = new byte[32];
            new SecureRandom().nextBytes(challenge);
            check.initSign(privateKey);
            check.update(challenge);
            byte[] signature = check.sign();
            check.initVerify(publicKey);
            check.update(challenge);
            if (!check.verify(signature)) throw new IllegalArgumentException();
            signingKey = new ECKey.Builder(active).privateKey(ecPrivate).build();
            Map<String, JWK> keys = new LinkedHashMap<>();
            keys.put(active.getKeyID(), active);
            for (PublicKey key : additional) {
                if (!(key instanceof ECPublicKey ec)) throw new IllegalArgumentException();
                ECKey jwk = publicKey(ec);
                keys.putIfAbsent(jwk.getKeyID(), jwk);
            }
            publicKeys = new JWKSet(new ArrayList<>(keys.values()));
        } catch (Exception failure) {
            throw new IllegalStateException("Claves JWT invalidas: se requiere pareja EC P-256 y solo publicas P-256");
        }
    }
    private static ECKey publicKey(ECPublicKey key) throws Exception {
        if (!Curve.P_256.equals(Curve.forECParameterSpec(key.getParams()))) throw new IllegalArgumentException();
        ECKey bare = new ECKey.Builder(Curve.P_256, key).build();
        return new ECKey.Builder(bare).keyID(bare.computeThumbprint("SHA-256").toString())
                .algorithm(JWSAlgorithm.ES256).keyUse(KeyUse.SIGNATURE).build();
    }
    public String activeKid() { return signingKey.getKeyID(); }
    public JwtEncoder encoder() {
        return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(signingKey)));
    }
    public NimbusJwtDecoder decoder() {
        DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
        processor.setJWSKeySelector(new JWSVerificationKeySelector<>(JWSAlgorithm.ES256,
                new ImmutableJWKSet<>(publicKeys)));
        processor.setJWTClaimsSetVerifier((claims, context) -> { });
        return new NimbusJwtDecoder(processor);
    }
    public Map<String, Object> publicJwks() { return publicKeys.toJSONObject(true); }
}
