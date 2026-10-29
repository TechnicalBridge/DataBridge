package com.tbridge.auth.security;

import org.springframework.beans.factory.annotation.*;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.stereotype.Service;
import java.time.*;

@Service
public class JwtService {
    private final JwtKeyRing keys;
    private final JwtEncoder encoder;
    private final JwtDecoder decoder;
    private final Duration vida;
    private final Clock clock;
    @Autowired
    public JwtService(JwtKeyRing keys, @Value("${jwt.ttl-minutes:15}") long minutes) {
        this(keys, minutes, Clock.systemUTC());
    }
    public JwtService(JwtKeyRing keys, long minutes, Clock clock) {
        if (minutes < 1) throw new IllegalStateException("jwt.ttl-minutes tiene que ser al menos 1");
        this.keys = keys;
        this.encoder = keys.encoder();
        this.decoder = JwtValidation.secure(keys.decoder(), clock);
        this.vida = Duration.ofMinutes(minutes);
        this.clock = clock;
    }
    public Duration vida() { return vida; }
    public String issue(String id, String email, String role, String name, String rut) {
        Instant now = clock.instant();
        var claims = JwtClaimsSet.builder().subject(id).claim("id", id).claim("role", role)
                .issuedAt(now).expiresAt(now.plus(vida));
        if (email != null) claims.claim("email", email);
        if (name != null) claims.claim("name", name);
        if (rut != null) claims.claim("rut", rut);
        var header = JwsHeader.with(SignatureAlgorithm.ES256).keyId(keys.activeKid()).build();
        return encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
    }
    public Jwt parse(String token) { return decoder.decode(token); }
    public JwtPrincipal toPrincipal(Jwt jwt) { return JwtPrincipal.fromClaims(jwt.getClaims()); }
}
