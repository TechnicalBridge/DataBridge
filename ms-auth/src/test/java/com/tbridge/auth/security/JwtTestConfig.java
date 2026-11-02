package com.tbridge.auth.security;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.*;
import java.util.List;

@TestConfiguration
@Import(JwtService.class)
public class JwtTestConfig {
    @Bean
    JwtKeyRing jwtKeyRing() { return ring(); }
    public static JwtKeyRing ring() {
        var pair = JwtTestTokens.PAIR;
        return new JwtKeyRing(pair.getPrivate(), pair.getPublic(), List.of());
    }
    public static JwtService service() { return new JwtService(ring(), 15); }
}
