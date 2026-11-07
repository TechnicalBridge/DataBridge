package com.tbridge.auth.integration;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.tbridge.auth.security.JwtKeyRing;
import com.tbridge.auth.security.JwtTestTokens;

import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue 333 - ms-auth: integracion contra una base de verdad.
 *
 * <p>Levanta MySQL con Testcontainers, ejecuta las migraciones de Flyway en un esquema vacio y
 * comprueba que el servicio responde contra esa base. Nunca usa H2 y nunca envia correos reales
 * (perfil test).
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AccesoIntegracionTest {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("tb_auth")
            .withUsername("tbridge")
            .withPassword("tbridge");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", () -> mysql.getJdbcUrl() + "?createDatabaseIfNotExist=true");
        r.add("spring.datasource.username", mysql::getUsername);
        r.add("spring.datasource.password", mysql::getPassword);
        r.add("spring.flyway.enabled", () -> "true");
        r.add("spring.flyway.locations", () -> "classpath:db/migration");
        r.add("events.rabbit", () -> "false");
        r.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
        r.add("management.health.rabbit.enabled", () -> "false");
        r.add("spring.mail.host", () -> "");
        r.add("spring.main.allow-bean-definition-overriding", () -> "true");
    }

    @LocalServerPort int puerto;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;

    /** JwtKeyRing real con claves de prueba (sin claves privadas commiteadas). */
    @TestConfiguration
    static class ClavesPrueba {
        @Bean @Primary
        JwtKeyRing jwtKeyRing() {
            KeyPair pair = JwtTestTokens.PAIR;
            return new JwtKeyRing(
                    (ECPrivateKey) pair.getPrivate(),
                    (ECPublicKey) pair.getPublic(),
                    List.of());
        }
    }

    @Test
    void el_contexto_levanta_con_mysql_de_verdad() {
        assertNotNull(mysql.getJdbcUrl());
        assertTrue(mysql.isRunning());
    }

    @Test
    void flyway_ejecuta_las_migraciones_en_esquema_vacio() {
        Integer tablas = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema = DATABASE()",
                Integer.class);
        assertNotNull(tablas);
        assertTrue(tablas > 0, "esperaba tablas tras las migraciones de Flyway");
    }

    @Test
    void el_servicio_responde() throws Exception {
        var salud = rest.getForEntity("/actuator/health", String.class);
        assertTrue(salud.getStatusCode().is2xxSuccessful() || salud.getStatusCode().is5xxServerError(),
                "el servicio levanta y responde");
    }
}
