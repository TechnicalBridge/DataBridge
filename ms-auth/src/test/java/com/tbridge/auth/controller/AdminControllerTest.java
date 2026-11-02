package com.tbridge.auth.controller;

import com.tbridge.auth.config.SecurityConfig;
import com.tbridge.auth.dto.response.AccesoResponse;
import com.tbridge.auth.dto.response.AccesosDeHoyResponse;
import com.tbridge.auth.dto.response.AccionResponse;
import com.tbridge.auth.dto.response.PersonaResponse;
import com.tbridge.auth.dto.response.ReglasResponse;
import com.tbridge.auth.exception.ApiException;
import com.tbridge.auth.model.AccessLog;
import com.tbridge.auth.security.JwtPrincipal;
import com.tbridge.auth.security.JwtService;
import com.tbridge.auth.service.BitacoraDeAccesos;
import com.tbridge.auth.service.PersonalService;
import com.tbridge.auth.service.ReglasService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** El panel de administracion es solo del admin de DataBridge: ni una empresa ni un deudor entran. */
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@WebMvcTest(AdminController.class)
@Import({SecurityConfig.class, com.tbridge.auth.security.JwtTestConfig.class})
@ActiveProfiles("test")
class AdminControllerTest {

    private static final String ESTADO = "/api/admin/auth/estado";
    private static final String DE_LA_EMPRESA = "/api/admin/auth/empresas/76086428-5/personal";
    private static final String ADMINS = "/api/admin/auth/admins";
    private static final String ANA = "{\"correo\":\"ana.soto@empresatest.cl\",\"nombre\":\"Ana Soto\"}";

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @MockitoBean private PersonalService personal;
    @MockitoBean private ReglasService reglas;
    @MockitoBean private BitacoraDeAccesos accesos;

    private String admin() {
        return "Bearer " + jwt.issue("9", "admin@databridge.cl", "ADMIN", "Administrador de DataBridge", null);
    }

    private String empresa() {
        return "Bearer " + jwt.issue("1", "camila.reyes@apofyx.cl", "CREDITOR", "Camila Reyes", "77305118-6");
    }

    private String deudor() {
        return "Bearer " + jwt.issue("16482337-7", null, "DEBTOR", null, "16482337-7");
    }

    private static PersonaResponse persona(long id, String correo, String rol, String empresa, boolean habilitada) {
        return new PersonaResponse(id, correo, "Alguien", rol, empresa, habilitada,
                Instant.parse("2026-10-13T15:00:00Z"), null, habilitada ? null : Instant.parse("2026-10-13T16:00:00Z"));
    }

    @Test
    void las_reglas_las_ve_el_admin_y_una_empresa_no() throws Exception {
        when(reglas.reglas()).thenReturn(new ReglasResponse("ms-auth", List.of()));

        mvc.perform(get("/api/admin/auth/reglas").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servicio").value("ms-auth"));
        mvc.perform(get("/api/admin/auth/reglas").header("Authorization", empresa()))
                .andExpect(status().isForbidden());
    }

    @Test
    void el_admin_de_databridge_entra() throws Exception {
        mvc.perform(get(ESTADO).header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servicio").value("ms-auth"))
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void el_usuario_de_una_empresa_recibe_403() throws Exception {
        mvc.perform(get(ESTADO).header("Authorization", empresa()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("No tienes permiso para esto"));
    }

    @Test
    void un_deudor_recibe_403() throws Exception {
        mvc.perform(get(ESTADO).header("Authorization", deudor()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("No tienes permiso para esto"));
    }

    @Test
    void sin_sesion_es_401() throws Exception {
        mvc.perform(get(ESTADO))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("No autorizado"));
    }

    @Test
    void una_ruta_del_panel_que_no_existe_tampoco_se_le_abre_a_otro_rol() throws Exception {
        //  La regla es de toda la ruta /api/admin, no de cada metodo: un endpoint
        //  nuevo no puede quedar abierto por olvido.
        mvc.perform(get("/api/admin/auth/lo-que-venga").header("Authorization", empresa()))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    //  La bitacora de acceso (#141)
    // ------------------------------------------------------------------

    private static final String ACCESOS = "/api/admin/auth/accesos";

    @Test
    void el_admin_ve_la_bitacora_por_pagina_sin_nada_que_identifique_a_la_persona() throws Exception {
        //  Dos codigos invalidos contra el mismo RUT y un enlace rechazado: la pagina 0 de 2, de a 3.
        List<AccesoResponse> fila = List.of(
                new AccesoResponse(Instant.parse("2026-10-27T15:03:00Z"), "code", "invalid", "18.***.***-6"),
                new AccesoResponse(Instant.parse("2026-10-27T15:02:00Z"), "code", "invalid", "18.***.***-6"),
                new AccesoResponse(Instant.parse("2026-10-27T15:01:00Z"), "magic_link", "refused", null));
        when(accesos.buscar(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(fila, PageRequest.of(0, 3), 5));

        mvc.perform(get(ACCESOS).param("porPagina", "3").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.accesos.length()").value(3))
                .andExpect(jsonPath("$._embedded.accesos[0].rut").value("18.***.***-6"))
                .andExpect(jsonPath("$._embedded.accesos[0].resultado").value("invalid"))
                .andExpect(jsonPath("$._embedded.accesos[2].resultado").value("refused"))
                .andExpect(jsonPath("$._embedded.accesos[2].rut").doesNotExist())
                .andExpect(jsonPath("$._embedded.accesos[0].ip").doesNotExist())
                .andExpect(jsonPath("$._embedded.accesos[0].email").doesNotExist())
                .andExpect(jsonPath("$.page.size").value(3))
                .andExpect(jsonPath("$.page.totalElements").value(5))
                .andExpect(jsonPath("$.page.totalPages").value(2))
                .andExpect(jsonPath("$.page.number").value(0))
                .andExpect(jsonPath("$._links.next.href", containsString("pagina=1")))
                .andExpect(jsonPath("$._links.next.href", containsString("porPagina=3")))
                .andExpect(jsonPath("$._links.last.href", containsString("pagina=1")))
                .andExpect(jsonPath("$._links.prev").doesNotExist());
    }

    @Test
    void los_filtros_llegan_tal_cual_y_quedan_en_los_enlaces_de_la_pagina() throws Exception {
        when(accesos.buscar(any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(2, 20), 60));

        mvc.perform(get(ACCESOS).header("Authorization", admin())
                        .param("desde", "2026-10-01").param("hasta", "2026-10-27")
                        .param("metodo", "code").param("resultado", "invalid")
                        .param("pagina", "2").param("porPagina", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded").doesNotExist())
                .andExpect(jsonPath("$._links.prev.href", containsString("pagina=1")))
                .andExpect(jsonPath("$._links.prev.href", containsString("resultado=invalid")))
                .andExpect(jsonPath("$._links.prev.href", containsString("desde=2026-10-01")));
        verify(accesos).buscar(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-27"),
                AccessLog.Method.code, AccessLog.Outcome.invalid, 2, 20);
    }

    @Test
    void un_resultado_que_no_existe_responde_400_sin_consultar() throws Exception {
        mvc.perform(get(ACCESOS).param("resultado", "cualquiera").header("Authorization", admin()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("El parametro 'resultado' no tiene el formato esperado"));
        verifyNoInteractions(accesos);
    }

    @Test
    void los_totales_del_dia() throws Exception {
        when(accesos.hoy()).thenReturn(new AccesosDeHoyResponse(LocalDate.parse("2026-10-27"), 5, 4, 7,
                Map.of("granted", 5L, "invalid", 3L, "refused", 1L, "sent", 7L)));

        mvc.perform(get(ACCESOS + "/hoy").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dia").value("2026-10-27"))
                .andExpect(jsonPath("$.entraron").value(5))
                .andExpect(jsonPath("$.rechazados").value(4))
                .andExpect(jsonPath("$.enviados").value(7))
                .andExpect(jsonPath("$.porResultado.invalid").value(3));
    }

    @Test
    void ni_una_empresa_ni_un_deudor_ven_la_bitacora() throws Exception {
        for (String sesion : List.of(empresa(), deudor())) {
            mvc.perform(get(ACCESOS).header("Authorization", sesion)).andExpect(status().isForbidden());
            mvc.perform(get(ACCESOS + "/hoy").header("Authorization", sesion)).andExpect(status().isForbidden());
        }
        mvc.perform(get(ACCESOS)).andExpect(status().isUnauthorized());
        verifyNoInteractions(accesos);
    }

    // ------------------------------------------------------------------
    //  Las personas
    // ------------------------------------------------------------------

    @Test
    void cada_persona_trae_solo_lo_que_se_puede_hacer_con_ella() throws Exception {
        when(personal.deEmpresa("76086428-5")).thenReturn(List.of(
                persona(21, "ana.soto@empresatest.cl", "usuario", "76086428-5", true),
                persona(22, "luis.vera@empresatest.cl", "usuario", "76086428-5", false)));

        mvc.perform(get(DE_LA_EMPRESA).header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.personas[0].correo").value("ana.soto@empresatest.cl"))
                .andExpect(jsonPath("$._embedded.personas[0].rol").value("usuario"))
                .andExpect(jsonPath("$._embedded.personas[0]._links.deshabilitar.href",
                        endsWith("/api/admin/auth/personal/21/deshabilitar")))
                .andExpect(jsonPath("$._embedded.personas[0]._links.habilitar").doesNotExist())
                .andExpect(jsonPath("$._embedded.personas[1].habilitada").value(false))
                .andExpect(jsonPath("$._embedded.personas[1]._links.habilitar.href").exists())
                .andExpect(jsonPath("$._embedded.personas[1]._links.deshabilitar").doesNotExist());
    }

    @Test
    void el_admin_de_la_sesion_no_trae_como_quitarse_el_acceso() throws Exception {
        when(personal.admins()).thenReturn(List.of(
                persona(9, "admin@databridge.cl", "admin", null, true),
                persona(10, "otra@databridge.cl", "admin", null, true)));

        mvc.perform(get(ADMINS).header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.personas[0]._links.deshabilitar").doesNotExist())
                .andExpect(jsonPath("$._embedded.personas[1]._links.deshabilitar.href").exists())
                .andExpect(jsonPath("$._embedded.personas[0].empresaRut").doesNotExist());
    }

    @Test
    void dar_de_alta_a_una_persona_responde_201_y_quien_lo_hizo_sale_de_la_sesion() throws Exception {
        when(personal.crearUsuario(eq("76086428-5"), any(), any()))
                .thenReturn(persona(21, "ana.soto@empresatest.cl", "usuario", "76086428-5", true));

        mvc.perform(post(DE_LA_EMPRESA).header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON).content(ANA))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rol").value("usuario"))
                .andExpect(jsonPath("$.empresaRut").value("76086428-5"));

        ArgumentCaptor<JwtPrincipal> quien = ArgumentCaptor.forClass(JwtPrincipal.class);
        verify(personal).crearUsuario(eq("76086428-5"), any(), quien.capture());
        assertEquals("admin@databridge.cl", quien.getValue().email());
        assertEquals("9", quien.getValue().id());
    }

    @Test
    void por_la_ruta_de_una_empresa_no_se_puede_pedir_un_admin() throws Exception {
        //  El cuerpo no tiene rol: aunque venga uno, no se lee. Lo que decide es la ruta,
        //  y la de una empresa crea siempre un usuario de esa empresa.
        when(personal.crearUsuario(eq("76086428-5"), any(), any()))
                .thenReturn(persona(21, "ana.soto@empresatest.cl", "usuario", "76086428-5", true));

        mvc.perform(post(DE_LA_EMPRESA).header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"correo\":\"ana.soto@empresatest.cl\",\"nombre\":\"Ana Soto\",\"rol\":\"admin\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.rol").value("usuario"));
        verify(personal, never()).crearAdmin(any(), any());
    }

    @Test
    void sin_correo_o_sin_nombre_se_rechaza_con_el_motivo() throws Exception {
        mvc.perform(post(DE_LA_EMPRESA).header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\":\"Ana Soto\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Falta el correo de la persona"));
        mvc.perform(post(ADMINS).header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"correo\":\"otra@databridge.cl\",\"nombre\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Falta el nombre de la persona"));
        verifyNoInteractions(personal);
    }

    @Test
    void un_correo_que_ya_tiene_cuenta_responde_409_con_el_motivo() throws Exception {
        when(personal.crearAdmin(any(), any())).thenThrow(new ApiException(HttpStatus.CONFLICT,
                "Ese correo es de una persona de una empresa: no se le puede dar el rol admin"));

        mvc.perform(post(ADMINS).header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON).content(ANA))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(
                        "Ese correo es de una persona de una empresa: no se le puede dar el rol admin"));
    }

    @Test
    void quitar_y_devolver_el_acceso_responden_con_la_persona_y_lo_que_sigue() throws Exception {
        when(personal.deshabilitar(eq(21L), any()))
                .thenReturn(persona(21, "ana.soto@empresatest.cl", "usuario", "76086428-5", false));
        when(personal.habilitar(eq(21L), any()))
                .thenReturn(persona(21, "ana.soto@empresatest.cl", "usuario", "76086428-5", true));

        mvc.perform(post("/api/admin/auth/personal/21/deshabilitar").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.habilitada").value(false))
                .andExpect(jsonPath("$._links.habilitar.href").exists());
        mvc.perform(post("/api/admin/auth/personal/21/habilitar").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.habilitada").value(true))
                .andExpect(jsonPath("$._links.deshabilitar.href").exists());
    }

    @Test
    void ni_una_empresa_ni_un_deudor_ni_nadie_sin_sesion_tocan_a_las_personas() throws Exception {
        for (String sesion : List.of(empresa(), deudor())) {
            mvc.perform(get(DE_LA_EMPRESA).header("Authorization", sesion)).andExpect(status().isForbidden());
            mvc.perform(get(ADMINS).header("Authorization", sesion)).andExpect(status().isForbidden());
            mvc.perform(post(DE_LA_EMPRESA).header("Authorization", sesion)
                    .contentType(MediaType.APPLICATION_JSON).content(ANA)).andExpect(status().isForbidden());
            mvc.perform(post(ADMINS).header("Authorization", sesion)
                    .contentType(MediaType.APPLICATION_JSON).content(ANA)).andExpect(status().isForbidden());
            mvc.perform(post("/api/admin/auth/personal/1/deshabilitar").header("Authorization", sesion))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post(ADMINS).contentType(MediaType.APPLICATION_JSON).content(ANA))
                .andExpect(status().isUnauthorized());
        //  Camila, de una empresa, no puede sacar a nadie ni hacerse admin.
        verifyNoInteractions(personal);
    }

    @Test
    void el_historial_dice_quien_hizo_que_y_a_quien() throws Exception {
        when(personal.historialDeEmpresa("76086428-5")).thenReturn(List.of(new AccionResponse(
                "persona_deshabilitada", "ana.soto@empresatest.cl", "admin@databridge.cl",
                Instant.parse("2026-10-13T16:00:00Z"))));
        when(personal.historialDeAdmins()).thenReturn(List.of());

        mvc.perform(get("/api/admin/auth/empresas/76086428-5/historial").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.acciones[0].accion").value("persona_deshabilitada"))
                .andExpect(jsonPath("$._embedded.acciones[0].sobre").value("ana.soto@empresatest.cl"))
                .andExpect(jsonPath("$._embedded.acciones[0].quien").value("admin@databridge.cl"));
        //  Sin acciones no viene _embedded, y eso es una lista vacia.
        mvc.perform(get("/api/admin/auth/admins/historial").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded").doesNotExist());
    }
}
