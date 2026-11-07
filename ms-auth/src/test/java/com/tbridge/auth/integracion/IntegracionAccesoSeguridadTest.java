package com.tbridge.auth.integracion;

import com.tbridge.auth.client.DeudasClient;
import com.tbridge.auth.model.AccessCode;
import com.tbridge.auth.model.MagicLink;
import com.tbridge.auth.repository.AccessCodeRepository;
import com.tbridge.auth.repository.MagicLinkRepository;
import com.tbridge.auth.security.JwtService;
import com.tbridge.auth.security.JwtTestConfig;
import com.tbridge.auth.util.Hash;
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
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integracion y seguridad de ms-auth contra una base de MySQL real.
 *
 * <p>El servicio se levanta completo contra el MySQL del entorno (puerto 3308,
 * levantado por docker-compose del repositorio), Flyway ejecuta las migraciones
 * y los controladores responden contra la base de verdad. El correo nunca sale
 * a un servidor real: con {@code spring.mail.host} vacio, los codigos y
 * enlaces quedan en el log.
 *
 * <p>Las pruebas de acceso al portal (pedir/canjear codigo y enlace) viven en
 * {@link AccesoControladorWebTest}, que usa {@link WebMvcTest} con el
 * {@link IdentifierLimit} mockeado para no mezclar el tope de intentos con el
 * agotamiento del codigo.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.main.allow-bean-definition-overriding=true")
@Import({JwtTestConfig.class, IntegracionAccesoSeguridadTest.JwtTestKeyConfig.class})
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Rollback(true)
class IntegracionAccesoSeguridadTest {

    private static final String RUT_DEUDOR = "18905214-6";
    private static final String CORREO_DEUDOR = "valentina.soto@correo.cl";
    private static final String RUT_EMPRESA = "76418902-7";
    private static final String CLAVE_INTERNA = "clave-interna-de-prueba";

    static final Path DIR_CLAVES;
    static {
        try {
            DIR_CLAVES = Files.createTempDirectory("auth-jwt-test-keys");
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
            System.setProperty("spring.main.allow-bean-definition-overriding", "true");
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
                () -> "jdbc:mysql://127.0.0.1:3308/tb_auth?useUnicode=true&characterEncoding=utf8&serverTimezone=America/Santiago");
        propiedades.add("spring.datasource.username", () -> "tbridge");
        propiedades.add("spring.datasource.password",
                () -> "Ise8YR3DW-IvnTl6VrDoLuHMFlb8aH53aggfAy5d6Tqe_1kKEoKKXmRk-lBjSlXM");
        propiedades.add("spring.flyway.enabled", () -> "true");
        propiedades.add("app.demo.datos", () -> "false");
        propiedades.add("app.internal-key", () -> CLAVE_INTERNA);
        propiedades.add("app.public-url", () -> "http://localhost:8080");
        propiedades.add("app.cookie-secure", () -> "false");
        propiedades.add("spring.mail.host", () -> "");
        propiedades.add("spring.main.allow-bean-definition-overriding", () -> "true");
    }

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired AccessCodeRepository codigos;
    @Autowired MagicLinkRepository enlaces;
    @Autowired JwtService jwtService;
    @MockBean DeudasClient deudas;

    @BeforeEach
    void prepararMocksYLimpiar() {
        enlaces.deleteAll();
        codigos.deleteAll();
        when(deudas.correoDelDeudor(RUT_DEUDOR)).thenReturn(Optional.of(CORREO_DEUDOR));
        when(deudas.empresa(RUT_EMPRESA))
                .thenReturn(Optional.of(new DeudasClient.Empresa(RUT_EMPRESA, "Empresa Test", true)));
        when(deudas.correoDelDeudor(anyString())).thenReturn(Optional.of(CORREO_DEUDOR));
    }

    // ------------------------------------------------------------------
    //  Migraciones
    // ------------------------------------------------------------------

    @Test
    @Order(1)
    void las_migraciones_estan_aplicadas_en_mysql_de_verdad() {
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1", String.class))
                .isEqualTo("8");
    }

    // ------------------------------------------------------------------
    //  Seguridad: token sin firma / otra llave, admin, inyeccion, me
    // ------------------------------------------------------------------

    @Test
    @Order(2)
    void token_sin_firma_o_con_otra_llave_es_401() throws Exception {
        String sinFirma = com.tbridge.auth.security.JwtTestTokens.token(
                com.tbridge.auth.security.JwtTestTokens.claims(),
                com.tbridge.auth.security.JwtTestTokens.PAIR,
                com.tbridge.auth.security.JwtTestTokens.publicKey(com.tbridge.auth.security.JwtTestTokens.PAIR).getKeyID(),
                "none");
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + sinFirma))
                .andExpect(status().isUnauthorized());

        var otraLlave = com.tbridge.auth.security.JwtTestTokens.pair("secp256r1");
        String firmadoConOtra = com.tbridge.auth.security.JwtTestTokens.token(
                com.tbridge.auth.security.JwtTestTokens.claims(),
                otraLlave,
                com.tbridge.auth.security.JwtTestTokens.publicKey(com.tbridge.auth.security.JwtTestTokens.PAIR).getKeyID(),
                "ES256");
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + firmadoConOtra))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(3)
    void solo_admin_entra_al_panel_admin() throws Exception {
        String adminToken = jwtService.issue("admin-1", "admin@test.cl", "ADMIN", "Admin", null);
        String empresaToken = jwtService.issue("emp-1", "emp@test.cl", "CREDITOR", "Empresa", RUT_EMPRESA);

        mvc.perform(get("/api/admin/auth/estado").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/auth/estado").header("Authorization", "Bearer " + empresaToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/auth/estado").header("Authorization", "Bearer " + jwtService.issue("d-1", "d@test.cl",
                "DEBTOR", "Deudor", RUT_DEUDOR)))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(4)
    void inyeccion_en_rut_y_correo_es_400() throws Exception {
        mvc.perform(post("/api/auth/acceso")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"rut\":\"1'; DROP TABLE users; --\",\"codigo\":\"ABC\"}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/auth/codigo")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"correo\":\"a@b.com'; DROP TABLE users; --\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @Order(5)
    void me_devuelve_quien_soy_con_sesion_valida() throws Exception {
        String token = jwtService.issue(RUT_DEUDOR, CORREO_DEUDOR, "DEBTOR", "Valentina", RUT_DEUDOR);
        mvc.perform(get("/api/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.rut").value(RUT_DEUDOR))
                .andExpect(jsonPath("$.user.role").value("DEBTOR"));
    }

    // ------------------------------------------------------------------
    //  Enlace de un solo uso (creado directamente en la base)
    // ------------------------------------------------------------------

    @Test
    @Order(6)
    void enlace_sirve_una_sola_vez() throws Exception {
        String token = "enlace-" + System.currentTimeMillis();
        MagicLink enlace = new MagicLink();
        enlace.setTokenHash(Hash.sha256(token));
        enlace.setDebtorRut(RUT_DEUDOR);
        enlace.setEmail(CORREO_DEUDOR);
        enlace.setExpiresAt(Instant.now().plusSeconds(900));
        enlace.setCodeHash(Hash.sha256(CORREO_DEUDOR + ":CODIGO1"));
        enlace.setLinkEnabled(true);
        enlaces.save(enlace);

        mvc.perform(post("/api/auth/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty());

        mvc.perform(post("/api/auth/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(7)
    void enlace_admitido_o_vacio_no_entra() throws Exception {
        mvc.perform(post("/api/auth/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"token-que-no-existe\"}"))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/auth/verify")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"\"}"))
                .andExpect(status().isBadRequest());
    }

    /** Configuracion de prueba que provee el anillo de claves JWT con prioridad. */
    @org.springframework.boot.test.context.TestConfiguration
    static class JwtTestKeyConfig {
        @org.springframework.context.annotation.Bean @org.springframework.context.annotation.Primary
        com.tbridge.auth.security.JwtKeyRing jwtKeyRing() {
            return JwtTestConfig.ring();
        }
    }
}
