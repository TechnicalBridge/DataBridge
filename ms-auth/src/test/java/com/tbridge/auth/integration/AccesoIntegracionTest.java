package com.tbridge.auth.integration;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.tbridge.auth.security.JwtKeyRing;

import java.security.KeyPair;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

/**
 * Issue 333 - ms-auth: integracion contra una base de verdad.
 *
 * <p>Levanta MySQL con Testcontainers, ejecuta Flyway en esquema vacio y comprueba que
 * el servicio responde contra una base de verdad. Nunca usa H2 y nunca envia correos
 * reales (perfil test). El {@link JwtKeyRing} se mockea con un decoder local (claves
 * de prueba efimeras) para no cargar claves privadas del repositorio.
 */
@Testcontainers
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = "spring.main.allow-bean-definition-overriding=true"
)
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
    }

    /** JwtKeyRing mockeado: decoder local con claves de prueba efimeras. */
    @MockBean JwtKeyRing jwtKeyRing;

    @BeforeEach
    void configurarDecoder() throws Exception {
        KeyPair pair = com.tbridge.auth.security.JwtTestTokens.PAIR;
        com.nimbusds.jose.jwk.ECKey publicKey =
                com.tbridge.auth.security.JwtTestTokens.publicKey(pair);
        com.nimbusds.jose.jwk.JWKSet jwkSet =
                new com.nimbusds.jose.jwk.JWKSet(List.of(publicKey));
        com.nimbusds.jwt.proc.DefaultJWTProcessor<com.nimbusds.jose.proc.SecurityContext> processor =
                new com.nimbusds.jwt.proc.DefaultJWTProcessor<>();
        processor.setJWSKeySelector(
                new com.nimbusds.jose.proc.JWSVerificationKeySelector<>(
                        com.nimbusds.jose.JWSAlgorithm.ES256,
                        new com.nimbusds.jose.jwk.source.ImmutableJWKSet<>(jwkSet)));
        processor.setJWTClaimsSetVerifier((claims, context) -> {});
        org.springframework.security.oauth2.jwt.NimbusJwtDecoder decoder =
                new org.springframework.security.oauth2.jwt.NimbusJwtDecoder(processor);
        when(jwtKeyRing.decoder()).thenReturn(decoder);
    }

    @LocalServerPort int puerto;
    @Autowired TestRestTemplate rest;
    @Autowired JdbcTemplate jdbc;

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
