package com.tbridge.auth.security;

import com.tbridge.auth.integration.BaseIntegracionMySql;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import java.security.KeyPair;
import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de seguridad de los controladores de ms-auth contra MySQL real.
 *
 * <p>Los casos son los de la hoja Pruebas de seguridad del plan: un token sin
 * firma o con otra llave, un enlace repetido o adivinado, inyeccion en el RUT y
 * en el correo, y {@code /internal} sin su clave. Ninguna prueba manda un
 * correo: {@link com.tbridge.auth.service.MailService} es un mock.
 */
@Tag("seguridad")
@EnabledIf("com.tbridge.auth.integration.BaseIntegracionMySql#hayBaseDeDatos")
class SeguridadControladoresTest extends BaseIntegracionMySql {

    // ------------------------------------------------------------------
    //  Tokens
    // ------------------------------------------------------------------

    @Test
    void el_panel_sin_token_responde_401() throws Exception {
        mvc.perform(get("/api/admin/auth/estado"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/auth/estado").header("Authorization", "Bearer "))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/auth/estado").header("Authorization", "Basic YWRtaW46YWRtaW4="))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void un_token_sin_firma_se_rechaza() throws Exception {
        String sinFirma = JwtTestTokens.token(JwtTestTokens.claims(), JwtTestTokens.PAIR,
                JwtTestTokens.publicKey(JwtTestTokens.PAIR).getKeyID(), "none");

        mvc.perform(get("/api/admin/auth/estado").header("Authorization", "Bearer " + sinFirma))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void un_token_firmado_con_otra_llave_se_rechaza() throws Exception {
        KeyPair ajeno = JwtTestTokens.pair("secp256r1");
        String firmado = JwtTestTokens.token(JwtTestTokens.claims(), ajeno,
                JwtTestTokens.publicKey(ajeno).getKeyID(), "ES256");

        mvc.perform(get("/api/admin/auth/estado").header("Authorization", "Bearer " + firmado))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void un_token_con_otro_algoritmo_se_rechaza() throws Exception {
        String hs256 = JwtTestTokens.token(JwtTestTokens.claims(), JwtTestTokens.PAIR,
                JwtTestTokens.publicKey(JwtTestTokens.PAIR).getKeyID(), "HS256");
        String rs256 = JwtTestTokens.token(JwtTestTokens.claims(), JwtTestTokens.PAIR,
                JwtTestTokens.publicKey(JwtTestTokens.PAIR).getKeyID(), "RS256");

        mvc.perform(get("/api/admin/auth/estado").header("Authorization", "Bearer " + hs256))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/auth/estado").header("Authorization", "Bearer " + rs256))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void un_token_vencido_se_rechaza() throws Exception {
        Map<String, Object> claims = new HashMap<>(JwtTestTokens.claims());
        Instant ahora = Instant.now();
        claims.put("iat", Date.from(ahora.minusSeconds(1200)));
        claims.put("exp", Date.from(ahora.minusSeconds(300)));
        String vencido = JwtTestTokens.token(claims, JwtTestTokens.PAIR,
                JwtTestTokens.publicKey(JwtTestTokens.PAIR).getKeyID(), "ES256");

        mvc.perform(get("/api/admin/auth/estado").header("Authorization", "Bearer " + vencido))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void un_token_con_rol_o_identidad_inesperados_se_rechaza() throws Exception {
        Map<String, Object> rolMalo = new HashMap<>(JwtTestTokens.claims());
        rolMalo.put("role", "SUPERADMIN");
        String conRolMalo = JwtTestTokens.token(rolMalo, JwtTestTokens.PAIR,
                JwtTestTokens.publicKey(JwtTestTokens.PAIR).getKeyID(), "ES256");

        Map<String, Object> sinIdentidad = new HashMap<>(JwtTestTokens.claims());
        sinIdentidad.remove("rut");
        String sinIdentidadFirmado = JwtTestTokens.token(sinIdentidad, JwtTestTokens.PAIR,
                JwtTestTokens.publicKey(JwtTestTokens.PAIR).getKeyID(), "ES256");

        mvc.perform(get("/api/admin/auth/estado").header("Authorization", "Bearer " + conRolMalo))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/auth/estado").header("Authorization", "Bearer " + sinIdentidadFirmado))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void un_deudor_o_una_empresa_no_entran_al_panel() throws Exception {
        mvc.perform(get("/api/admin/auth/estado").header("Authorization", "Bearer " + tokenDeudor()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/auth/estado").header("Authorization", "Bearer " + tokenEmpresa()))
                .andExpect(status().isForbidden());

        // El admin si entra: el 403 de arriba es por el rol, no por el token.
        mvc.perform(get("/api/admin/auth/admins").header("Authorization", "Bearer " + tokenAdmin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.personas[*].correo", hasItem(ADMIN_EMAIL)));
    }

    // ------------------------------------------------------------------
    //  Enlaces
    // ------------------------------------------------------------------

    @Test
    void un_enlace_repetido_o_adivinado_no_abre_sesion() throws Exception {
        String token = pedirEnlaceDeDeudor(RUT_DEUDOR);

        mvc.perform(post("/api/auth/verify")
                        .with(origen())
                        .contentType(APPLICATION_JSON)
                        .content(cuerpoJson(Map.of("token", token))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("DEBTOR"));

        // Repetido: ya se consumio.
        mvc.perform(post("/api/auth/verify")
                        .with(origen())
                        .contentType(APPLICATION_JSON)
                        .content(cuerpoJson(Map.of("token", token))))
                .andExpect(status().isUnauthorized());

        // Adivinado: un UUID al azar no corresponde a ningun pedido.
        mvc.perform(post("/api/auth/verify")
                        .with(origen())
                        .contentType(APPLICATION_JSON)
                        .content(cuerpoJson(Map.of("token", UUID.randomUUID().toString()))))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    //  Inyeccion en RUT y correo
    // ------------------------------------------------------------------

    @Test
    void la_inyeccion_en_el_rut_no_llega_a_la_base() throws Exception {
        String inyeccion = "18905214-6' OR 1=1";

        mvc.perform(post("/api/auth/acceso")
                        .with(origen())
                        .contentType(APPLICATION_JSON)
                        .content(cuerpoJson(Map.of("rut", inyeccion, "codigo", "ABC123"))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/enlace")
                        .with(origen())
                        .contentType(APPLICATION_JSON)
                        .content(cuerpoJson(Map.of("rut", inyeccion))))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staff_users", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM access_codes", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sessions", Integer.class)).isZero();
    }

    @Test
    void la_inyeccion_en_el_correo_no_llega_a_la_base() throws Exception {
        String inyeccion = "admin@example.com' OR '1'='1";

        mvc.perform(post("/api/auth/codigo")
                        .with(origen())
                        .contentType(APPLICATION_JSON)
                        .content(cuerpoJson(Map.of("correo", inyeccion))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/auth/acceso")
                        .with(origen())
                        .contentType(APPLICATION_JSON)
                        .content(cuerpoJson(Map.of("correo", inyeccion, "codigo", "ABC123"))))
                .andExpect(status().isBadRequest());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM staff_users", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM magic_links", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sessions", Integer.class)).isZero();
    }

    // ------------------------------------------------------------------
    //  La clave interna y el JWKS
    // ------------------------------------------------------------------

    @Test
    void lo_interno_sin_su_clave_responde_401() throws Exception {
        String cuerpo = cuerpoJson(Map.of("rut", RUT_DEUDOR, "canales", List.of("whatsapp")));

        mvc.perform(post("/internal/codigos").contentType(APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/codigos")
                        .header("X-Internal-Key", "otra-clave")
                        .contentType(APPLICATION_JSON)
                        .content(cuerpo))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/correo/prueba").param("para", "equipo@example.com"))
                .andExpect(status().isUnauthorized());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM access_codes", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM access_log", Integer.class)).isZero();
    }

    @Test
    void el_jwks_es_publico_pero_no_expone_material_privado() throws Exception {
        mvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.keys[0].kty").value("EC"))
                .andExpect(jsonPath("$.keys[0].kid", not(blankOrNullString())))
                .andExpect(jsonPath("$.keys[0].d").doesNotExist())
                .andExpect(jsonPath("$.keys[0].p").doesNotExist())
                .andExpect(jsonPath("$.keys[0].q").doesNotExist());
    }
}
