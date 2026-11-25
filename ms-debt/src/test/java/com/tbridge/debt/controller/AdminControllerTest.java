package com.tbridge.debt.controller;

import com.tbridge.debt.dto.response.ComisionesResponse;
import com.tbridge.debt.dto.response.ContactoEstadoResponse;
import com.tbridge.debt.service.ComisionService;
import com.tbridge.debt.config.SecurityConfig;
import com.tbridge.debt.dto.response.AccionResponse;
import com.tbridge.debt.dto.response.AvisosResponse;
import com.tbridge.debt.dto.response.BitacoraResponse;
import com.tbridge.debt.dto.response.DeudaAdminResponse;
import com.tbridge.debt.dto.response.DeudorEncontradoResponse;
import com.tbridge.debt.dto.response.EmpresaResponse;
import com.tbridge.debt.dto.response.FichaDeudorResponse;
import com.tbridge.debt.dto.response.ReclamoAdminResponse;
import com.tbridge.debt.dto.response.PruebaSuscripcionResponse;
import com.tbridge.debt.dto.response.SecretoNuevoResponse;
import com.tbridge.debt.dto.response.SuscripcionAdminResponse;
import com.tbridge.debt.dto.response.ReglasResponse;
import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.Debtor;
import com.tbridge.debt.security.JwtTestIssuer;
import com.tbridge.debt.service.AvisosService;
import com.tbridge.debt.service.BitacoraService;
import com.tbridge.debt.service.DeudoresAdminService;
import com.tbridge.debt.service.EmpresaService;
import com.tbridge.debt.service.PasadasDeContacto;
import com.tbridge.debt.service.ReclamosAdminService;
import com.tbridge.debt.service.SuscripcionesAdminService;
import com.tbridge.debt.service.ReglasService;
import com.tbridge.debt.service.ResumenAdminService;
import com.tbridge.debt.dto.response.ResumenAdminResponse;
import com.tbridge.debt.dto.response.CampanaAdminResponse;
import org.junit.jupiter.api.Test;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.endsWith;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** El panel de administracion es solo del admin de DataBridge: ni una empresa ni un deudor entran. */
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@WebMvcTest(AdminController.class)
@Import({SecurityConfig.class, com.tbridge.debt.security.JwtTestConfig.class})
@ActiveProfiles("test")
class AdminControllerTest {

    private static final String ESTADO = "/api/admin/debt/estado";
    private static final String EMPRESAS = "/api/admin/debt/empresas";
    private static final String NUEVA = """
            {"rut":"76.086.428-5","razonSocial":"Empresa Test SpA","nombre":"Empresa Test","tipo":"acreedor"}""";

    @Autowired private MockMvc mvc;
    @Autowired private JwtTestIssuer jwt;
    @MockitoBean private EmpresaService empresas;
    @MockitoBean private ReglasService reglas;
    @MockitoBean private ComisionService comisiones;
    @MockitoBean private AvisosService avisos;
    @MockitoBean private BitacoraService bitacora;
    @MockitoBean private ResumenAdminService resumen;

    @Test
    void resumen_solo_admin() throws Exception {
        when(resumen.resumen()).thenReturn(new ResumenAdminResponse(2, 1, Map.of("open", 7L),
                LocalDate.of(2026, 11, 1), 12000, 1200));
        mvc.perform(get("/api/admin/debt/resumen").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deudasPorEstado.open").value(7))
                .andExpect(jsonPath("$.recuperado").value(12000));
        mvc.perform(get("/api/admin/debt/resumen").header("Authorization", empresa()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/debt/resumen").header("Authorization", deudor()))
                .andExpect(status().isForbidden());
    }

    @Test
    void campanas_solo_admin_y_sin_datos_personales() throws Exception {
        when(resumen.campanas()).thenReturn(List.of(
                new CampanaAdminResponse(1L, "Cobranza", "Agencia", "Acreedor", "running",
                        LocalDate.of(2026, 11, 1), null, 8, 5, 2, 9)));
        mvc.perform(get("/api/admin/debt/campanas").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].deudas").value(8))
                .andExpect(jsonPath("$[0].nombre").value("Cobranza"))
                .andExpect(jsonPath("$[0].acreedor").value("Acreedor"))
                .andExpect(jsonPath("$[0].deudasAbiertas").value(5))
                .andExpect(jsonPath("$[0].contactosHoy").value(2))
                .andExpect(jsonPath("$[0].contactosSemana").value(9));
        mvc.perform(get("/api/admin/debt/campanas").header("Authorization", empresa()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/debt/campanas").header("Authorization", deudor()))
                .andExpect(status().isForbidden());
    }
    @MockitoBean private DeudoresAdminService deudoresAdmin;
    @MockitoBean private ReclamosAdminService reclamosAdmin;
    @MockitoBean private SuscripcionesAdminService suscripcionesAdmin;
    @MockitoBean private PasadasDeContacto pasadas;

    @Test
    void el_estado_del_contacto_lo_ve_solo_el_admin() throws Exception {
        Instant inicio = Instant.parse("2026-11-20T12:00:00Z");
        when(pasadas.estado()).thenReturn(new ContactoEstadoResponse(inicio.minusSeconds(3600),
                new ContactoEstadoResponse.Pasada(inicio, inicio.plusSeconds(2), true, 3, 1, 2, null), null,
                inicio.plusSeconds(900), false, true, true));
        mvc.perform(get("/api/admin/debt/contacto/estado").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ultima.enviados").value(3))
                .andExpect(jsonPath("$.ultima.pendientes").value(2))
                .andExpect(jsonPath("$.atrasada").value(false))
                .andExpect(jsonPath("$.horarioLegal").value(true))
                .andExpect(jsonPath("$.limiteSemanal").value(true));
        mvc.perform(get("/api/admin/debt/contacto/estado").header("Authorization", empresa()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/debt/contacto/estado").header("Authorization", deudor()))
                .andExpect(status().isForbidden());
    }

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
                        LocalDate.of(2026, 1, 1), null, true, "vigente")));
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

    @Test
    void la_bitacora_de_deudas_usa_el_contrato_comun_y_es_solo_del_admin() throws Exception {
        BitacoraResponse fila = new BitacoraResponse(4L, "ms-debt", "empresa_suspendida",
                "APOFYX", "77305118-6", "admin@databridge.cl", Instant.parse("2026-11-17T15:00:00Z"));
        when(bitacora.buscar(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(fila), PageRequest.of(0, 25), 1));

        mvc.perform(get("/api/admin/debt/bitacora").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.acciones[0].servicio").value("ms-debt"))
                .andExpect(jsonPath("$._embedded.acciones[0].accion").value("empresa_suspendida"));
        mvc.perform(get("/api/admin/debt/bitacora").header("Authorization", deudor()))
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
    void lo_que_databridge_cobra_a_cada_empresa_lo_ve_solo_el_admin() throws Exception {
        when(comisiones.deTodas(java.time.YearMonth.of(2026, 10))).thenReturn(new ComisionesResponse(
                java.time.LocalDate.of(2026, 10, 1), java.util.List.of(new ComisionesResponse.Empresa("77305118-6", "APOFYX",
                1_420_000, 4, new java.math.BigDecimal("10"), 142_000, 1_278_000)), 1_420_000, 142_000));

        mvc.perform(get("/api/admin/debt/comisiones").param("mes", "2026-10").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.empresas[0].nombre").value("APOFYX"))
                .andExpect(jsonPath("$.empresas[0].comision").value(142000))
                .andExpect(jsonPath("$.comision").value(142000));
        mvc.perform(get("/api/admin/debt/comisiones").header("Authorization", empresa()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/debt/comisiones").header("Authorization", deudor()))
                .andExpect(status().isForbidden());
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

    @Test
    void suspender_y_reactivar_desde_el_panel() throws Exception {
        when(empresas.suspender(eq("77305118-6"), any())).thenReturn(new EmpresaResponse(
                "77305118-6", "APOFYX SpA", "APOFYX", "agencia", false,
                Instant.parse("2026-09-01T12:00:00Z"), 12, 9, List.of(), List.of()));
        when(empresas.reactivar(eq("77305118-6"), any())).thenReturn(new EmpresaResponse(
                "77305118-6", "APOFYX SpA", "APOFYX", "agencia", true,
                Instant.parse("2026-09-01T12:00:00Z"), 12, 9, List.of(), List.of()));

        mvc.perform(post(EMPRESAS + "/77305118-6/suspender").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activa").value(false));

        mvc.perform(post(EMPRESAS + "/77305118-6/reactivar").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activa").value(true));
    }

    @Test
    void una_empresa_o_un_deudor_no_pueden_suspender_o_reactivar() throws Exception {
        mvc.perform(post(EMPRESAS + "/77305118-6/suspender").header("Authorization", empresa()))
                .andExpect(status().isForbidden());
        mvc.perform(post(EMPRESAS + "/77305118-6/suspender").header("Authorization", deudor()))
                .andExpect(status().isForbidden());
        mvc.perform(post(EMPRESAS + "/77305118-6/reactivar").header("Authorization", empresa()))
                .andExpect(status().isForbidden());
        mvc.perform(post(EMPRESAS + "/77305118-6/reactivar").header("Authorization", deudor()))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    //  Los avisos a las empresas (#139)
    // ------------------------------------------------------------------

    @Test
    void el_admin_ve_los_avisos_sin_entregar_con_sus_intentos_y_su_ultimo_error() throws Exception {
        when(avisos.sinEntregar()).thenReturn(new AvisosResponse(0, 21, List.of(new AvisosResponse.Destino(1L,
                "APOFYX", "77305118-6", "http://host.docker.internal:8000", 0, 21,
                List.of(new AvisosResponse.Aviso(40L, "pago.confirmado", Instant.parse("2026-10-20T15:00:00Z"),
                        "failed", 6, null, "Connection refused"))))));

        mvc.perform(get("/api/admin/debt/avisos").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fallidos").value(21))
                .andExpect(jsonPath("$.destinos[0].empresa").value("APOFYX"))
                .andExpect(jsonPath("$.destinos[0].direccion").value("http://host.docker.internal:8000"))
                .andExpect(jsonPath("$.destinos[0].avisos[0].intentos").value(6))
                .andExpect(jsonPath("$.destinos[0].avisos[0].ultimoError").value("Connection refused"))
                .andExpect(jsonPath("$.destinos[0].avisos[0].payload").doesNotExist());
    }

    @Test
    void reintentar_un_aviso_anota_al_admin_de_la_sesion() throws Exception {
        when(avisos.reintentar(40L, "admin@databridge.cl")).thenReturn(new AvisosResponse.Aviso(40L,
                "pago.confirmado", Instant.parse("2026-10-20T15:00:00Z"), "pending", 6,
                Instant.parse("2026-10-27T18:00:00Z"), "Connection refused"));

        mvc.perform(post("/api/admin/debt/avisos/40/reintentar").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("pending"));
        verify(avisos).reintentar(40L, "admin@databridge.cl");
    }

    @Test
    void reintentar_un_destinatario_dice_cuantos_fueron() throws Exception {
        when(avisos.reintentarDestino(1L, "admin@databridge.cl")).thenReturn(21);

        mvc.perform(post("/api/admin/debt/avisos/destinos/1/reintentar").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reintentados").value(21));
    }

    @Test
    void un_aviso_ya_entregado_responde_409_con_el_motivo() throws Exception {
        when(avisos.reintentar(eq(41L), any())).thenThrow(new ApiException(HttpStatus.CONFLICT, "Ese aviso ya se entrego"));

        mvc.perform(post("/api/admin/debt/avisos/41/reintentar").header("Authorization", admin()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Ese aviso ya se entrego"));
    }

    @Test
    void ni_una_empresa_ni_un_deudor_ven_ni_reintentan_avisos() throws Exception {
        for (String sesion : List.of(empresa(), deudor())) {
            mvc.perform(get("/api/admin/debt/avisos").header("Authorization", sesion)).andExpect(status().isForbidden());
            mvc.perform(post("/api/admin/debt/avisos/40/reintentar").header("Authorization", sesion))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/admin/debt/avisos/destinos/1/reintentar").header("Authorization", sesion))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(avisos);
    }

    @Test
    void el_admin_busca_a_un_deudor_y_abre_su_ficha_y_ni_una_empresa_ni_un_deudor_pueden() throws Exception {
        when(deudoresAdmin.buscar("camila", 0, 20)).thenReturn(new PageImpl<>(List.of(new DeudorEncontradoResponse(
                "16482337-7", "Camila Fuentes Rojas", "camila@example.com", 2, List.of("Patrimonio Inmuebles"))),
                PageRequest.of(0, 20), 1));
        when(deudoresAdmin.ficha("16482337-7", "admin@databridge.cl")).thenReturn(new FichaDeudorResponse(
                "16482337-7", "Camila Fuentes Rojas", Debtor.Kind.person, "camila@example.com", null, true,
                new FichaDeudorResponse.Correo(null, null, 0, false), List.of(), List.of()));

        mvc.perform(get("/api/admin/debt/deudores").param("q", "camila").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.deudores[0].rut").value("16482337-7"))
                .andExpect(jsonPath("$._embedded.deudores[0].deudas").value(2))
                .andExpect(jsonPath("$.page.totalElements").value(1));
        mvc.perform(get("/api/admin/debt/deudores/16482337-7").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("Camila Fuentes Rojas"))
                .andExpect(jsonPath("$.estadoDelCorreo.yaNoSeLeEscribe").value(false));

        for (String otro : List.of(empresa(), deudor())) {
            mvc.perform(get("/api/admin/debt/deudores").param("q", "camila").header("Authorization", otro))
                    .andExpect(status().isForbidden());
            mvc.perform(get("/api/admin/debt/deudores/16482337-7").header("Authorization", otro))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/admin/debt/deudores/16482337-7")).andExpect(status().isUnauthorized());
        verify(deudoresAdmin, times(1)).ficha(any(), any());
        verify(deudoresAdmin, times(1)).buscar(any(), eq(0), eq(20));
    }

    @Test
    void la_deuda_de_un_pago_la_ve_solo_el_admin() throws Exception {
        when(deudoresAdmin.deuda(15L)).thenReturn(new DeudaAdminResponse(15L, "CTR-007", "Arriendo",
                "Patrimonio Inmuebles", "76418902-7", "APOFYX", "Arriendos", Debt.Status.paid));

        mvc.perform(get("/api/admin/debt/deudas/15").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referencia").value("CTR-007"))
                .andExpect(jsonPath("$.cobra").value("APOFYX"));
        for (String otro : List.of(empresa(), deudor())) {
            mvc.perform(get("/api/admin/debt/deudas/15").header("Authorization", otro))
                    .andExpect(status().isForbidden());
        }
        verify(deudoresAdmin, times(1)).deuda(any());
    }

    @Test
    void el_admin_ve_los_reclamos_y_ni_una_empresa_ni_un_deudor_pueden() throws Exception {
        ReclamoAdminResponse abierto = new ReclamoAdminResponse(15L, "CTR-2024-007", "Arriendo", "Patrimonio Inmuebles",
                "APOFYX", "Arriendos septiembre", "ya_pagada", "<b>Pague</b> en la oficina",
                Instant.parse("2026-11-10T15:00:00Z"), 8, null, null, null, null);
        when(reclamosAdmin.abiertos(0, 20)).thenReturn(new PageImpl<>(List.of(abierto), PageRequest.of(0, 20), 1));
        when(reclamosAdmin.resueltos(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 18), 0, 20))
                .thenReturn(new PageImpl<>(List.of(), PageRequest.of(0, 20), 0));

        mvc.perform(get("/api/admin/debt/reclamos").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.reclamos[0].deuda").value(15))
                .andExpect(jsonPath("$._embedded.reclamos[0].cobra").value("APOFYX"))
                .andExpect(jsonPath("$._embedded.reclamos[0].dias").value(8))
                //  El texto del deudor va tal cual: el portal lo muestra como texto.
                .andExpect(jsonPath("$._embedded.reclamos[0].detalle").value("<b>Pague</b> en la oficina"))
                .andExpect(jsonPath("$._embedded.reclamos[0].rut").doesNotExist())
                .andExpect(jsonPath("$.page.totalElements").value(1));
        mvc.perform(get("/api/admin/debt/reclamos/resueltos").param("desde", "2026-11-01").param("hasta", "2026-11-18")
                        .header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page.totalElements").value(0));

        for (String otro : List.of(empresa(), deudor())) {
            mvc.perform(get("/api/admin/debt/reclamos").header("Authorization", otro)).andExpect(status().isForbidden());
            mvc.perform(get("/api/admin/debt/reclamos/resueltos").header("Authorization", otro))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/admin/debt/reclamos")).andExpect(status().isUnauthorized());
        verify(reclamosAdmin, times(1)).abiertos(0, 20);
        verify(reclamosAdmin, times(1)).resueltos(any(), any(), eq(0), eq(20));
    }

    @Test
    void el_admin_maneja_las_suscripciones_y_ni_una_empresa_ni_un_deudor_pueden() throws Exception {
        when(suscripcionesAdmin.listar()).thenReturn(List.of(new SuscripcionAdminResponse(1L, "APOFYX", "77305118-6",
                "https://apofyx.cl/eventos", List.of(), false, Instant.parse("2026-10-01T12:00:00Z"), null, 2, 0)));
        when(suscripcionesAdmin.cambiarSecreto(1L, "admin@databridge.cl"))
                .thenReturn(new SecretoNuevoResponse(1L, "whsec_nuevo", "X-Firma: v1=..."));
        when(suscripcionesAdmin.probar(1L, "admin@databridge.cl"))
                .thenReturn(new PruebaSuscripcionResponse(1L, true, 202, 40, null));

        mvc.perform(get("/api/admin/debt/suscripciones").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.suscripciones[0].url").value("https://apofyx.cl/eventos"))
                .andExpect(jsonPath("$._embedded.suscripciones[0].activa").value(false))
                .andExpect(jsonPath("$._embedded.suscripciones[0].pendientes").value(2))
                .andExpect(jsonPath("$._embedded.suscripciones[0].secreto").doesNotExist());
        mvc.perform(post("/api/admin/debt/suscripciones/1/activar").header("Authorization", admin()))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/admin/debt/suscripciones/1/desactivar").header("Authorization", admin()))
                .andExpect(status().isNoContent());
        mvc.perform(post("/api/admin/debt/suscripciones/1/secreto").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.secreto").value("whsec_nuevo"));
        mvc.perform(post("/api/admin/debt/suscripciones/1/probar").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value(202));
        verify(suscripcionesAdmin).activar(1L, "admin@databridge.cl");
        verify(suscripcionesAdmin).desactivar(1L, "admin@databridge.cl");

        for (String otro : List.of(empresa(), deudor())) {
            mvc.perform(get("/api/admin/debt/suscripciones").header("Authorization", otro))
                    .andExpect(status().isForbidden());
            for (String accion : List.of("activar", "desactivar", "secreto", "probar")) {
                mvc.perform(post("/api/admin/debt/suscripciones/1/" + accion).header("Authorization", otro))
                        .andExpect(status().isForbidden());
            }
        }
        mvc.perform(post("/api/admin/debt/suscripciones/1/secreto")).andExpect(status().isUnauthorized());
        verify(suscripcionesAdmin, times(1)).cambiarSecreto(any(), any());
        verify(suscripcionesAdmin, times(1)).probar(any(), any());
    }
}
