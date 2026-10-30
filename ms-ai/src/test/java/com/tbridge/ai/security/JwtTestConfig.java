package com.tbridge.ai.security;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import java.time.Clock;
import java.util.List;

@TestConfiguration
public class JwtTestConfig {
    @Bean @Primary
    JwtDecoder testJwtDecoder() {
        return JwtValidation.secure(JwtTestTokens.localDecoder(
                List.of(JwtTestTokens.publicKey(JwtTestTokens.PAIR))),Clock.systemUTC());
    }
    @Bean JwtTestIssuer jwtTestIssuer() { return new JwtTestIssuer(); }
}
