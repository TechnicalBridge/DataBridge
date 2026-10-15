package com.tbridge.debt.controller;

import com.tbridge.debt.config.SecurityConfig;
import com.tbridge.debt.dto.response.AccionResponse;
import com.tbridge.debt.dto.response.EmpresaResponse;
import com.tbridge.debt.dto.response.ReglasResponse;
import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.security.JwtService;
import com.tbridge.debt.service.EmpresaService;
import com.tbridge.debt.service.ReglasService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** El panel de administracion es solo del admin de DataBridge: ni una empresa ni un deudor entran. */
@WebMvcTest(AdminController.class)
@Import({SecurityConfig.class, JwtService.class})
@ActiveProfiles("test")
class AdminControllerTest {

    private static final String ESTADO = "/api/admin/debt/estado";
    private static final String EMPRESAS = "/api/admin/debt/empresas";
    private static final String NUEVA = """
            {"rut":"76.086.428-5","razonSocial":"Empresa Test SpA","nombre":"Empresa Test","tipo":"acreedor"}""";

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @MockitoBean private EmpresaService empresas;
    @MockitoBean private ReglasService reglas;

    private String admin() {
        return "Bearer " + jwt.issue("9", "admin@databridge.cl", "ADMIN", "Administrador de DataBridge", null);
    }

    private String empresa() {
        return "Bearer " + jwt.issue("1", "camila.reyes@apofyx.cl", "CREDITOR", "Camila Reyes", "77305118-6");
    }

    private String deudor() {
        return "Bearer " + jwt.issue("16482337-7", null, "DEBTOR", null, "16482337-7");
    }

    private static EmpresaResponse apofyx() {
        return new EmpresaResponse("77305118-6", "APOFYX SpA", "APOFYX", "agencia", true,
                Instant.parse("2026-09-01T12:00:00Z"), 12, 9,
                List.of(new EmpresaResponse.Monto("CLP", new BigDecimal("4250000"))),
                List.of(new EmpresaResponse.Mandato("cobra_para", "76418902-7", "Patrimonio Inmuebles",
                        LocalDate.of(2026, 1, 1), null, true)));
    }

    @Test
    void el_admin_de_databridge_entra() throws Exception {
        mvc.perform(get(ESTADO).header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servicio").value("ms-debt"))
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
    void las_reglas_las_ve_el_admin_y_una_empresa_no() throws Exception {
        when(reglas.reglas(any())).thenReturn(new ReglasResponse("ms-debt", List.of()));

        mvc.perform(get("/api/admin/debt/reglas").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servicio").value("ms-debt"));
        mvc.perform(get("/api/admin/debt/reglas").header("Authorization", empresa()))
                .andExpect(status().isForbidden());
    }

    @Test
    void una_ruta_del_panel_que_no_existe_tampoco_se_le_abre_a_otro_rol() throws Exception {
        //  La regla es de toda la ruta /api/admin, no de cada metodo: un endpoint
        //  nuevo no puede quedar abierto por olvido.
        mvc.perform(get("/api/admin/debt/lo-que-venga").header("Authorization", empresa()))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    //  Las empresas
    // ------------------------------------------------------------------

    @Test
    void el_admin_ve_las_empresas_con_sus_totales_y_sin_ningun_deudor() throws Exception {
        when(empresas.listar()).thenReturn(List.of(apofyx()));

        mvc.perform(get(EMPRESAS).header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.empresas[0].rut").value("77305118-6"))
                .andExpect(jsonPath("$._embedded.empresas[0].tipo").value("agencia"))
                .andExpect(jsonPath("$._embedded.empresas[0].deudas").value(12))
                .andExpect(jsonPath("$._embedded.empresas[0].enGestion").value(9))
                .andExpect(jsonPath("$._embedded.empresas[0].porCobrar[0].monto").value(4250000))
                .andExpect(jsonPath("$._embedded.empresas[0].mandatos[0].papel").value("cobra_para"))
                .andExpect(jsonPath("$._embedded.empresas[0]._links.self.href", endsWith("/empresas/77305118-6")))
                .andExpect(jsonPath("$._embedded.empresas[0]._links.historial.href").exists())
                //  Totales, y nada mas: ni deudas ni deudores.
                .andExpect(jsonPath("$._embedded.empresas[0].deudores").doesNotExist());
    }

    @Test
    void ni_una_empresa_ni_un_deudor_ven_la_lista_de_empresas() throws Exception {
        mvc.perform(get(EMPRESAS).header("Authorization", empresa())).andExpect(status().isForbidden());
        mvc.perform(get(EMPRESAS).header("Authorization", deudor())).andExpect(status().isForbidden());
        mvc.perform(get(EMPRESAS + "/77305118-6").header("Authorization", empresa())).andExpect(status().isForbidden());
        verify(empresas, never()).listar();
        verify(empresas, never()).ficha(any());
    }

    @Test
    void dar_de_alta_responde_201_con_la_empresa_y_anota_al_admin_de_la_sesion() throws Exception {
        when(empresas.crear(any(), eq("admin@databridge.cl"))).thenReturn(new EmpresaResponse(
                "76086428-5", "Empresa Test SpA", "Empresa Test", "acreedor", true, Instant.now(), 0, 0,
                List.of(), List.of()));

        mvc.perform(post(EMPRESAS).header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON).content(NUEVA))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", endsWith("/api/admin/debt/empresas/76086428-5")))
                .andExpect(jsonPath("$.rut").value("76086428-5"))
                .andExpect(jsonPath("$.deudas").value(0));
        //  Quien lo hizo sale de la sesion, no del cuerpo: no se puede anotar a nombre de otro.
        verify(empresas).crear(any(), eq("admin@databridge.cl"));
    }

    @Test
    void una_empresa_no_puede_dar_de_alta_a_otra() throws Exception {
        mvc.perform(post(EMPRESAS).header("Authorization", empresa())
                        .contentType(MediaType.APPLICATION_JSON).content(NUEVA))
                .andExpect(status().isForbidden());
        mvc.perform(post(EMPRESAS).contentType(MediaType.APPLICATION_JSON).content(NUEVA))
                .andExpect(status().isUnauthorized());
        verify(empresas, never()).crear(any(), any());
    }

    @Test
    void sin_rut_sin_razon_social_o_con_otro_tipo_se_rechaza_con_el_motivo() throws Exception {
        mvc.perform(post(EMPRESAS).header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"razonSocial\":\"Empresa Test SpA\",\"tipo\":\"agencia\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Falta el RUT de la empresa"));
        mvc.perform(post(EMPRESAS).header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rut\":\"76086428-5\",\"razonSocial\":\" \",\"tipo\":\"agencia\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Falta la razon social"));
        mvc.perform(post(EMPRESAS).header("Authorization", admin()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rut\":\"76086428-5\",\"razonSocial\":\"Empresa Test SpA\",\"tipo\":\"admin\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("El tipo es agencia o acreedor"));
        verify(empresas, never()).crear(any(), any());
    }

    @Test
    void un_rut_repetido_responde_409_con_el_motivo() throws Exception {
        when(empresas.crear(any(), any())).thenThrow(new ApiException(HttpStatus.CONFLICT,
                "Ya hay una empresa con ese RUT: APOFYX. Abre su ficha para agregarle personas."));

        mvc.perform(post(EMPRESAS).header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON).content(NUEVA))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value(
                        "Ya hay una empresa con ese RUT: APOFYX. Abre su ficha para agregarle personas."));
    }

    @Test
    void el_historial_de_una_empresa_dice_quien_hizo_que() throws Exception {
        when(empresas.historial("76086428-5")).thenReturn(List.of(new AccionResponse(
                "empresa_creada", "Empresa Test", "admin@databridge.cl", Instant.parse("2026-10-13T15:00:00Z"))));

        mvc.perform(get(EMPRESAS + "/76086428-5/historial").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.acciones[0].accion").value("empresa_creada"))
                .andExpect(jsonPath("$._embedded.acciones[0].quien").value("admin@databridge.cl"))
                .andExpect(jsonPath("$._embedded.acciones[0].cuando").value("2026-10-13T15:00:00Z"));
    }
}
