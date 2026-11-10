package com.tbridge.auth.integration;

import com.tbridge.auth.dto.response.PruebaDeCorreoResponse;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integracion de los controladores de ms-auth contra MySQL real.
 *
 * <p>Cubren las migraciones sobre una base vacia, el acceso con codigo y con
 * enlace, el enlace de un solo uso, el codigo que se agota a los cinco
 * intentos, y las rutas del panel, del contrato interno y del JWKS. El correo
 * y ms-debt se reemplazan por mocks: ninguna prueba abre SMTP.
 */
@Tag("integracion")
@EnabledIf("com.tbridge.auth.integration.BaseIntegracionMySql#hayBaseDeDatos")
class IntegracionControladoresTest extends BaseIntegracionMySql {

    // ------------------------------------------------------------------
    //  Migraciones
    // ------------------------------------------------------------------

    @Test
    void las_migraciones_se_aplican_sobre_un_mysql_vacio() {
        Integer tablas = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name IN ('access_codes', 'magic_links', 'staff_users', 'access_log',
                                     'sessions', 'admin_log', 'flyway_schema_history')
                """, Integer.class);
        assertThat(tablas).isEqualTo(7);

        Integer aplicadas = jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class);
        assertThat(aplicadas).isEqualTo(8);
        assertThat(jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1", String.class))
                .isEqualTo("8");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 0", Integer.class)).isZero();
    }

    // ------------------------------------------------------------------
    //  AuthController: codigo y enlace
    // ------------------------------------------------------------------

    @Test
    void el_deudor_entra_con_el_codigo_de_campana_y_ese_codigo_sirve_una_sola_vez() throws Exception {
        String codigo = codigoInterno(RUT_DEUDOR);

        MvcResult respuesta = mvc.perform(post("/api/auth/acceso")
                        .with(origen())
                        .contentType(APPLICATION_JSON)
                        .content(cuerpoJson(Map.of("rut", RUT_DEUDOR, "codigo", codigo))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", not(blankOrNullString())))
                .andExpect(jsonPath("$.user.role").value("DEBTOR"))
                .andExpect(jsonPath("$.user.rut").value(RUT_DEUDOR))
                .andExpect(jsonPath("$.expiraEnSegundos").value(900))
                .andReturn();

        var cookie = respuesta.getResponse().getCookie("tb_renovacion");
        assertThat(cookie).isNotNull();
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.getPath()).isEqualTo("/api/auth");
        assertThat(cookie.getValue()).isNotBlank();

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM access_codes
                WHERE debtor_rut = ? AND consumed_at IS NOT NULL
                """, Integer.class, RUT_DEUDOR)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM access_log
                WHERE debtor_rut = ? AND method = 'code' AND outcome = 'granted'
                """, Integer.class, RUT_DEUDOR)).isEqualTo(1);

        // El mismo codigo, otra vez: 401. De un solo uso.
        mvc.perform(post("/api/auth/acceso")
                        .with(origen())
                        .contentType(APPLICATION_JSON)
                        .content(cuerpoJson(Map.of("rut", RUT_DEUDOR, "codigo", codigo))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void el_personal_entra_con_el_codigo_que_llega_a_su_correo() throws Exception {
        String codigo = pedirCodigoDePersonal(EMPRESA_EMAIL);

        mvc.perform(post("/api/auth/acceso")
                        .with(origen())
                        .contentType(APPLICATION_JSON)
                        .content(cuerpoJson(Map.of("correo", EMPRESA_EMAIL, "codigo", codigo))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", not(blankOrNullString())))
                .andExpect(jsonPath("$.user.role").value("CREDITOR"))
                .andExpect(jsonPath("$.user.correo").value(EMPRESA_EMAIL))
                .andExpect(jsonPath("$.user.rut").value(RUT_EMPRESA));

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM staff_users WHERE email = ? AND last_login_at IS NOT NULL
                """, Integer.class, EMPRESA_EMAIL)).isEqualTo(1);
    }

    @Test
    void el_enlace_del_deudor_abre_una_sesion_una_sola_vez() throws Exception {
        String token = pedirEnlaceDeDeudor(RUT_DEUDOR);

        mvc.perform(post("/api/auth/verify")
                        .with(origen())
                        .contentType(APPLICATION_JSON)
                        .content(cuerpoJson(Map.of("token", token))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token", not(blankOrNullString())))
                .andExpect(jsonPath("$.user.role").value("DEBTOR"))
                .andExpect(jsonPath("$.user.rut").value(RUT_DEUDOR));

        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM magic_links
                WHERE debtor_rut = ? AND consumed_at IS NOT NULL
                """, Integer.class, RUT_DEUDOR)).isEqualTo(1);

        // El mismo enlace, otra vez: 401. No es un segundo factor, es un uso.
        mvc.perform(post("/api/auth/verify")
                        .with(origen())
                        .contentType(APPLICATION_JSON)
                        .content(cuerpoJson(Map.of("token", token))))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void el_codigo_se_agota_a_los_cinco_intentos() throws Exception {
        String correcto = codigoInterno(RUT_DEUDOR);
        String errado = codigoErrado(correcto);

        for (int intento = 0; intento < 5; intento++) {
            mvc.perform(post("/api/auth/acceso")
                            .with(origen())
                            .contentType(APPLICATION_JSON)
                            .content(cuerpoJson(Map.of("rut", RUT_DEUDOR, "codigo", errado))))
                    .andExpect(status().isUnauthorized());
        }

        // Ni con el codigo correcto: la fila ya esta agotada.
        mvc.perform(post("/api/auth/acceso")
                        .with(origen())
                        .contentType(APPLICATION_JSON)
                        .content(cuerpoJson(Map.of("rut", RUT_DEUDOR, "codigo", correcto))))
                .andExpect(status().isTooManyRequests());

        assertThat(jdbc.queryForObject("""
                SELECT attempts FROM access_codes WHERE debtor_rut = ? ORDER BY id DESC LIMIT 1
                """, Integer.class, RUT_DEUDOR)).isEqualTo(5);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM access_codes WHERE debtor_rut = ? AND consumed_at IS NULL
                """, Integer.class, RUT_DEUDOR)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM access_log
                WHERE debtor_rut = ? AND method = 'code' AND outcome = 'exhausted'
                """, Integer.class, RUT_DEUDOR)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    //  AdminController
    // ------------------------------------------------------------------

    @Test
    void el_admin_consulta_su_panel_y_da_de_alta_personas_en_la_base() throws Exception {
        String token = tokenAdmin();

        mvc.perform(get("/api/admin/auth/estado").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servicio").value("ms-auth"))
                .andExpect(jsonPath("$.ok").value(true));

        mvc.perform(get("/api/admin/auth/empresas/" + RUT_EMPRESA + "/personal")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.personas[*].correo", hasItem(EMPRESA_EMAIL)));

        String nuevo = "nueva.persona@empresa.cl";
        mvc.perform(post("/api/admin/auth/empresas/" + RUT_EMPRESA + "/personal")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(cuerpoJson(Map.of("correo", nuevo, "nombre", "Nueva Persona"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.correo").value(nuevo))
                .andExpect(jsonPath("$.nombre").value("Nueva Persona"))
                .andExpect(jsonPath("$.rol").value("usuario"))
                .andExpect(jsonPath("$.empresaRut").value(RUT_EMPRESA))
                .andExpect(jsonPath("$.habilitada").value(true));

        // La invitacion se arma y se entrega al mock: no hay correo real.
        verify(correo).enviarInvitacion(eq(nuevo), eq("Nueva Persona"), anyString(), anyString(), eq(24L),
                anyString());
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM staff_users WHERE email = ? AND role = 'usuario' AND org_rut = ?
                """, Integer.class, nuevo, RUT_EMPRESA)).isEqualTo(1);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM admin_log
                WHERE action = 'persona_creada' AND subject = ? AND admin_email = ?
                """, Integer.class, nuevo, ADMIN_EMAIL)).isEqualTo(1);

        // El mismo correo, otra vez: 409, sin una segunda fila.
        mvc.perform(post("/api/admin/auth/empresas/" + RUT_EMPRESA + "/personal")
                        .header("Authorization", "Bearer " + token)
                        .contentType(APPLICATION_JSON)
                        .content(cuerpoJson(Map.of("correo", nuevo, "nombre", "Nueva Persona"))))
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM staff_users WHERE email = ?", Integer.class, nuevo)).isEqualTo(1);
    }

    // ------------------------------------------------------------------
    //  InternalController
    // ------------------------------------------------------------------

    @Test
    void el_interno_emite_codigos_y_prueba_el_correo_sin_mandar_un_correo_real() throws Exception {
        String codigo = codigoInterno(RUT_DEUDOR);
        assertThat(codigo).hasSize(6).matches("[2-9A-HJ-KMNP-Z]{6}");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM access_codes WHERE debtor_rut = ? AND consumed_at IS NULL
                """, Integer.class, RUT_DEUDOR)).isEqualTo(1);

        clearInvocations(correo);
        PruebaDeCorreoResponse respuesta = new PruebaDeCorreoResponse(true, "equipo@databridge.cl",
                "equipo@databridge.cl", "smtp.interno:0", "DataBridge <equipo@databridge.cl>",
                "modo de prueba", null);
        when(correo.probar("equipo@databridge.cl")).thenReturn(respuesta);

        mvc.perform(post("/internal/correo/prueba")
                        .param("para", "equipo@databridge.cl")
                        .header("X-Internal-Key", CLAVE_INTERNA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enviado").value(true))
                .andExpect(jsonPath("$.para").value("equipo@databridge.cl"))
                .andExpect(jsonPath("$.llegaA").value("equipo@databridge.cl"));

        // La prueba paso por el mock; ninguna conexion SMTP se abrio.
        verify(correo).probar("equipo@databridge.cl");
    }

    // ------------------------------------------------------------------
    //  JwksController
    // ------------------------------------------------------------------

    @Test
    void el_jwks_publica_solo_la_llave_publica() throws Exception {
        mvc.perform(get("/.well-known/jwks.json"))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.keys.length()").value(1))
                .andExpect(jsonPath("$.keys[0].kty").value("EC"))
                .andExpect(jsonPath("$.keys[0].crv").value("P-256"))
                .andExpect(jsonPath("$.keys[0].alg").value("ES256"))
                .andExpect(jsonPath("$.keys[0].use").value("sig"))
                .andExpect(jsonPath("$.keys[0].kid", not(blankOrNullString())))
                .andExpect(jsonPath("$.keys[0].d").doesNotExist());
    }

    /** Cambia un caracter para que el codigo sea seguro distinto del emitido. */
    private static String codigoErrado(String codigo) {
        char primera = codigo.charAt(0) == 'A' ? 'B' : 'A';
        return primera + codigo.substring(1);
    }
}
