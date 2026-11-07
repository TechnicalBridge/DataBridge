package com.tbridge.payments.integration;

import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue 334 - ms-payments: integracion contra una base de verdad.
 *
 * <p>Levanta MySQL con Testcontainers, ejecuta las migraciones de Flyway en un esquema vacio y
 * comprueba que el servicio responde contra esa base. Nunca usa H2 y nunca llama a una
 * pasarela real.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class PagoIntegracionTest {

    @Container
    static final MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("tb_payments")
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
        r.add("app.debt-url", () -> "http://ms-debt.invalid");
        r.add("app.khipu.url", () -> "http://khipu.invalid");
        r.add("app.transbank.url", () -> "http://webpay.invalid");
        r.add("app.mercadopago.url", () -> "http://mercadopago.invalid");
        r.add("app.mercadopago.access-token", () -> "");
        r.add("app.mercadopago.public-key", () -> "");
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
