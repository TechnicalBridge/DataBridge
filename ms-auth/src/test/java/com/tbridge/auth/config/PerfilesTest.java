package com.tbridge.auth.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Solo el perfil dev lee el .env (#327). Si lo leyera el application.properties
 * base, cualquier prueba que levante Spring veria las claves de verdad.
 */
class PerfilesTest {

    @Test
    void solo_el_perfil_dev_lee_el_env() throws IOException {
        assertFalse(leer("application.properties").contains("spring.config.import"));
        assertTrue(leer("application-dev.properties").contains("spring.config.import"));
    }

    private String leer(String archivo) throws IOException {
        try (InputStream entrada = getClass().getClassLoader().getResourceAsStream(archivo)) {
            return new String(entrada.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
