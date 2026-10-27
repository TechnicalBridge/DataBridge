package com.tbridge.auth.config;
import com.tbridge.auth.security.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import java.nio.file.Path;

@Configuration
public class JwtKeyConfig {
    @Bean
    JwtKeyRing jwtKeyRing(@Value("${jwt.key-directory}") String directory) {
        if (directory.isBlank()) throw new IllegalStateException("Falta JWT_KEY_DIRECTORY");
        return new JwtKeyLoader().load(Path.of(directory));
    }
}
