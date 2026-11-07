package com.tbridge.payments.integracion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.payments.client.DebtClient;
import com.tbridge.payments.security.JwtTestConfig;
import com.tbridge.payments.security.JwtTestTokens;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.spec.ECGenParameterSpec;
import java.time.Instant;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integracion y seguridad de ms-payments contra una base de MySQL real.
 *
 * <p>El servicio se levanta completo contra el MySQL del entorno (puerto 3310,
 * levantado por docker-compose del repositorio), Flyway ejecuta las migraciones
 * y los controladores responden contra la base de verdad. ms-debt y las
 * pasarelas se mockean para no salir a internet.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
@Import(JwtTestConfig.class)
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Rollback(true)
class IntegracionPagosSeguridadTest {

    private static final Path DIR_CLAVES;
    static {
        try {
            DIR_CLAVES = Files.createTempDirectory("payments-jwt-test-keys");
            KeyPairGenerator gen = KeyPairGenerator.getInstance("EC");
            gen.initialize(new ECGenParameterSpec("secp256r1"));
            var pair = gen.generateKeyPair();
            byte[] priv = pair.getPrivate().getEncoded();
            byte[] pub = pair.getPublic().getEncoded();
            Files.writeString(DIR_CLAVES.resolve("private.pem"),
                    "-----BEGIN PRIVATE KEY-----\n" + pem(priv) + "\n-----END PRIVATE KEY-----");
            Files.writeString(DIR_CLAVES.resolve("public.pem"),
                    "-----BEGIN PUBLIC KEY-----\n" + pem(pub) + "\n-----END PUBLIC KEY-----");
            System.setProperty("jwt.key-directory", DIR_CLAVES.toString());
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static String pem(byte[] der) {
        String b64 = Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b64.length(); i += 64) {
            sb.append(b64, i, Math.min(i + 64, b64.length())).append('\n');
        }
        return sb.toString();
    }

    @DynamicPropertySource
    static void baseDeDatos(DynamicPropertyRegistry propiedades) {
        propiedades.add("spring.datasource.url",
                () -> "jdbc:mysql://127.0.0.1:3310/tb_payments?useUnicode=true&characterEncoding=utf8&serverTimezone=America/Santiago");
        propiedades.add("spring.datasource.username", () -> "tbridge");
        propiedades.add("spring.datasource.password",
                () -> "OPHA2aKLnMSgw12s8Uy5EIP_LAZfDz3N5s1AnewS7DJhfhhpsdgb9UtNCfaivyeC");
        propiedades.add("spring.flyway.enabled", () -> "true");
        propiedades.add("app.demo.datos", () -> "false");
        propiedades.add("app.internal-key", () -> "clave-interna-de-prueba");
        propiedades.add("app.public-url", () -> "http://localhost:8080");
        propiedades.add("spring.mail.host", () -> "");
        //  El decodador real se sobrescribe con JwtTestConfig (@Primary), pero
        //  JwtConfig debe poder crear su bean remoto sin fallar en el arranque.
        propiedades.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:1/.well-known/jwks.json");
        propiedades.add("spring.main.allow-bean-definition-overriding", () -> "true");
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @MockBean DebtClient deudas;

    @BeforeEach
    void preparar() {
    }

    // ------------------------------------------------------------------
    //  Migraciones
    // ------------------------------------------------------------------

    @Test
    @Order(1)
    void las_migraciones_estan_aplicadas_en_mysql_de_verdad() {
        assertThat(flyway.info().applied()).isNotEmpty();
        assertThat(flyway.info().pending()).isEmpty();
    }

    // ------------------------------------------------------------------
    //  Seguridad: token sin firma o con otra llave
    // ------------------------------------------------------------------

    @Test
    @Order(2)
    void un_token_sin_firma_o_con_otra_llave_es_401() throws Exception {
        String sinFirma = JwtTestTokens.token(JwtTestTokens.claims(), JwtTestTokens.PAIR,
                JwtTestTokens.publicKey(JwtTestTokens.PAIR).getKeyID(), "none");
        mvc.perform(get("/api/payments").header("Authorization", "Bearer " + sinFirma))
                .andExpect(status().isUnauthorized());

        var otraLlave = JwtTestTokens.pair("secp256r1");
        String firmadoConOtra = JwtTestTokens.token(JwtTestTokens.claims(), otraLlave,
                JwtTestTokens.publicKey(JwtTestTokens.PAIR).getKeyID(), "ES256");
        mvc.perform(get("/api/payments").header("Authorization", "Bearer " + firmadoConOtra))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(3)
    void sin_sesion_no_entra() throws Exception {
        mvc.perform(get("/api/payments")).andExpect(status().isUnauthorized());
    }
}
