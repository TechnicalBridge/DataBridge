package com.tbridge.debt.controller;

import com.tbridge.debt.events.PagoConfirmado;
import com.tbridge.debt.security.JwtTestIssuer;
import com.tbridge.debt.config.SecurityConfig;
import com.tbridge.debt.dto.response.DebtSnapshotResponse;
import com.tbridge.debt.dto.response.OrganizacionResponse;
import com.tbridge.debt.dto.response.RecordatoriosResponse;
import com.tbridge.debt.service.ApiKeyService;
import com.tbridge.debt.service.CampanaAvanceService;
import com.tbridge.debt.service.DebtService;
import com.tbridge.debt.service.EmpresaService;
import com.tbridge.debt.service.ContactoService;
import com.tbridge.debt.service.RecordatorioService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Lo interno: sin la clave, nada; con ella, lo que ms-payments necesita. */
@WebMvcTest(InternalController.class)
@Import({SecurityConfig.class, com.tbridge.debt.security.JwtTestConfig.class})
@ActiveProfiles("test")
class InternalControllerTest {

    private static final String CLAVE = "clave-interna-de-prueba";

    @Autowired private MockMvc mvc;
    @MockitoBean private DebtService debts;
    @MockitoBean private CampanaAvanceService avances;
    @MockitoBean private ApiKeyService claves;
    @MockitoBean private RecordatorioService recordatorios;
    @MockitoBean private ContactoService contactos;
    @MockitoBean private EmpresaService empresas;

    @Test
    void ms_auth_pregunta_si_una_empresa_existe_antes_de_darle_una_persona() throws Exception {
        when(empresas.buscar("77305118-6")).thenReturn(Optional.of(new OrganizacionResponse("77305118-6", "APOFYX", true)));
        when(empresas.buscar("76086428-5")).thenReturn(Optional.empty());

        mvc.perform(get("/internal/organizaciones/77305118-6").header("X-Internal-Key", CLAVE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nombre").value("APOFYX"))
                .andExpect(jsonPath("$.activa").value(true));
        mvc.perform(get("/internal/organizaciones/76086428-5").header("X-Internal-Key", CLAVE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("No hay ninguna empresa con ese RUT"));
    }

    @Test
    void sin_la_clave_interna_no_se_dice_que_empresas_existen() throws Exception {
        mvc.perform(get("/internal/organizaciones/77305118-6")).andExpect(status().isUnauthorized());
        verify(empresas, never()).buscar(any());
    }

    @Test
    void sin_la_clave_interna_no_se_revela_ninguna_deuda() throws Exception {
        mvc.perform(get("/internal/debts/3")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/debts/3").header("X-Internal-Key", "otra")).andExpect(status().isUnauthorized());
        verify(debts, never()).snapshotInterno(any(), any());
    }

    @Test
    void con_la_clave_ms_payments_sabe_cuanto_cobrar() throws Exception {
        when(debts.snapshotInterno(3L, null)).thenReturn(new DebtSnapshotResponse(
                3L, "76418902-7", "18905214-6", "CLP", new BigDecimal("410000"), 12L, List.of(12L),
                new BigDecimal("410000"), BigDecimal.ZERO, BigDecimal.ZERO));

        mvc.perform(get("/internal/debts/3").header("X-Internal-Key", CLAVE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(410000))
                .andExpect(jsonPath("$.debtorRut").value("18905214-6"))
                .andExpect(jsonPath("$.installmentId").value(12));
    }

    @Test
    void las_cuotas_pedidas_llegan_como_lista() throws Exception {
        when(debts.snapshotInterno(3L, List.of(12L, 13L))).thenReturn(new DebtSnapshotResponse(
                3L, "76418902-7", "16482337-7", "CLP", new BigDecimal("280000"), null, List.of(12L, 13L),
                new BigDecimal("280000"), BigDecimal.ZERO, BigDecimal.ZERO));

        mvc.perform(get("/internal/debts/3").param("installmentIds", "12", "13").header("X-Internal-Key", CLAVE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.amount").value(280000))
                //  Con varias cuotas no va ninguna: el pago se imputa en orden.
                .andExpect(jsonPath("$.installmentId").doesNotExist())
                //  Pero si cuales cubre, para saber si dos pagos se pisan.
                .andExpect(jsonPath("$.installmentIds[0]").value(12))
                .andExpect(jsonPath("$.installmentIds[1]").value(13));
    }

    @Test
    void el_correo_registrado_de_un_deudor_va_detras_de_la_clave() throws Exception {
        when(debts.correoDelDeudor("16482337-7")).thenReturn(java.util.Optional.of("felipe.rojas@correo.cl"));
        when(debts.correoDelDeudor("11111111-1")).thenReturn(java.util.Optional.empty());

        mvc.perform(get("/internal/deudores/16482337-7/correo")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/deudores/16482337-7/correo").header("X-Internal-Key", CLAVE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correo").value("felipe.rojas@correo.cl"));
        mvc.perform(get("/internal/deudores/11111111-1/correo").header("X-Internal-Key", CLAVE))
                .andExpect(status().isNotFound());
    }

    @Test
    void la_cartera_de_una_empresa_va_detras_de_la_clave() throws Exception {
        when(debts.carteraInterna("77305118-6")).thenReturn(List.of(3L, 7L));

        mvc.perform(get("/internal/cartera/77305118-6")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/cartera/77305118-6").header("X-Internal-Key", CLAVE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").value(3))
                .andExpect(jsonPath("$[1]").value(7));
    }

    @Test
    void los_recordatorios_se_pueden_mandar_como_si_fuera_otro_dia() throws Exception {
        when(recordatorios.enviar(LocalDate.of(2026, 10, 17))).thenReturn(new RecordatoriosResponse(2, 1));

        mvc.perform(post("/internal/recordatorios").param("hoy", "2026-10-17").header("X-Internal-Key", CLAVE))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enviados").value(2))
                .andExpect(jsonPath("$.omitidos").value(1));
        mvc.perform(post("/internal/recordatorios")).andExpect(status().isUnauthorized());
    }

    @Test
    void el_aviso_de_pago_por_http_llega_entero() throws Exception {
        mvc.perform(post("/internal/events/pago-confirmado").header("X-Internal-Key", CLAVE)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"tipo":"pago.confirmado","paymentId":41,"debtId":3,"debtorRut":"18905214-6",
                                 "amount":410000,"currency":"CLP","amountClp":410000,"gateway":"webpay",
                                 "gatewayTxnId":"wp-1","paidAt":"2026-09-24T12:00:00Z"}"""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        ArgumentCaptor<PagoConfirmado> aviso = ArgumentCaptor.forClass(PagoConfirmado.class);
        verify(debts).onPagoConfirmado(aviso.capture());
        assertEquals(3L, aviso.getValue().debtId());
        assertEquals("wp-1", aviso.getValue().gatewayTxnId());
    }

    @Test
    void emitir_una_clave_pide_el_rut() throws Exception {
        mvc.perform(post("/internal/claves").header("X-Internal-Key", CLAVE)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nombre\":\"Demo\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Falta el RUT de la organizacion"));
    }
}
