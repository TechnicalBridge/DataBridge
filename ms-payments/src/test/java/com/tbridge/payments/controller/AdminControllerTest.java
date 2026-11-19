package com.tbridge.payments.controller;

import com.tbridge.payments.config.SecurityConfig;
import com.tbridge.payments.dto.response.OperacionResponse;
import com.tbridge.payments.dto.response.BitacoraResponse;
import com.tbridge.payments.dto.response.PagoAdminDetalleResponse;
import com.tbridge.payments.dto.response.PagoAdminResponse;
import com.tbridge.payments.dto.response.PagoVerificadoResponse;
import com.tbridge.payments.dto.response.PagoDeDeudorResponse;
import com.tbridge.payments.dto.response.ReglasResponse;
import com.tbridge.payments.dto.response.UfResponse;
import com.tbridge.payments.model.Payment;
import com.tbridge.payments.security.JwtTestIssuer;
import com.tbridge.payments.service.OperacionService;
import com.tbridge.payments.service.BitacoraService;
import com.tbridge.payments.service.PagosAdminService;
import com.tbridge.payments.service.PagosDelDeudorService;
import com.tbridge.payments.service.ReglasService;
import com.tbridge.payments.service.ResumenAdminService;
import com.tbridge.payments.dto.response.ResumenAdminResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.times;
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
@Import({SecurityConfig.class, com.tbridge.payments.security.JwtTestConfig.class})
@ActiveProfiles("test")
class AdminControllerTest {

    private static final String ESTADO = "/api/admin/payments/estado";

    @Autowired private MockMvc mvc;
    @Autowired private JwtTestIssuer jwt;
    @MockitoBean private ReglasService reglas;
    @MockitoBean private OperacionService operacion;
    @MockitoBean private BitacoraService bitacora;
    @MockitoBean private ResumenAdminService resumen;

    @Test
    void resumen_solo_admin() throws Exception {
        when(resumen.resumen()).thenReturn(new ResumenAdminResponse(LocalDate.of(2026, 11, 17),
                Map.of("webpay", 3L), Map.of("webpay", 17L), 2));
        mvc.perform(get("/api/admin/payments/resumen").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagosHoy.webpay").value(3))
                .andExpect(jsonPath("$.abiertosMasDeUnaHora").value(2));
        mvc.perform(get("/api/admin/payments/resumen").header("Authorization", empresa()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/payments/resumen").header("Authorization", deudor()))
                .andExpect(status().isForbidden());
    }
    @MockitoBean private PagosDelDeudorService pagosDelDeudor;
    @MockitoBean private PagosAdminService pagosAdmin;

    private String admin() {
        return "Bearer " + jwt.issue("9", "admin@databridge.cl", "ADMIN", "Administrador de DataBridge", null);
    }

    private String empresa() {
        return "Bearer " + jwt.issue("1", "camila.reyes@apofyx.cl", "CREDITOR", "Camila Reyes", "77305118-6");
    }

    private String deudor() {
        return "Bearer " + jwt.issue("16482337-7", null, "DEBTOR", null, "16482337-7");
    }

    @Test
    void el_admin_de_databridge_entra() throws Exception {
        mvc.perform(get(ESTADO).header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servicio").value("ms-payments"))
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void las_reglas_las_ve_el_admin_y_una_empresa_no() throws Exception {
        when(reglas.reglas()).thenReturn(new ReglasResponse("ms-payments", List.of()));

        mvc.perform(get("/api/admin/payments/reglas").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servicio").value("ms-payments"));
        mvc.perform(get("/api/admin/payments/reglas").header("Authorization", empresa()))
                .andExpect(status().isForbidden());
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
        mvc.perform(get("/api/admin/payments/lo-que-venga").header("Authorization", empresa()))
                .andExpect(status().isForbidden());
    }

    @Test
    void la_bitacora_de_pagos_usa_el_contrato_comun_y_es_solo_del_admin() throws Exception {
        BitacoraResponse fila = new BitacoraResponse(2L, "ms-payments", "uf_cargada",
                "2026-11-17: 40123.45", null, "admin@databridge.cl", Instant.parse("2026-11-17T15:00:00Z"));
        when(bitacora.buscar(any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(new PageImpl<>(List.of(fila), PageRequest.of(0, 25), 1));

        mvc.perform(get("/api/admin/payments/bitacora").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.acciones[0].servicio").value("ms-payments"))
                .andExpect(jsonPath("$._embedded.acciones[0].empresa").doesNotExist());
        mvc.perform(get("/api/admin/payments/bitacora").header("Authorization", empresa()))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    //  La operacion de los pagos (#139)
    // ------------------------------------------------------------------

    @Test
    void el_admin_ve_la_operacion_de_los_pagos() throws Exception {
        when(operacion.operacion()).thenReturn(new OperacionResponse(
                new OperacionResponse.Pagos(3, 1, 4, 1),
                new OperacionResponse.Uf(LocalDate.parse("2026-10-27"), false, null, null),
                new OperacionResponse.Avisos(0, 1, List.of(new OperacionResponse.Aviso(3L, 58L, "failed", 6, null,
                        "Connection refused"))), List.of(),
                List.of(new OperacionResponse.CobroAbierto(2488L, "mercadopago", 1309L, 173395L,
                        java.time.Instant.parse("2026-11-13T04:34:02Z")))));

        mvc.perform(get("/api/admin/payments/operacion").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagos.abiertos").value(3))
                .andExpect(jsonPath("$.pagos.duplicados").value(1))
                .andExpect(jsonPath("$.uf.cargada").value(false))
                .andExpect(jsonPath("$.avisos.lista[0].pago").value(58))
                .andExpect(jsonPath("$.avisos.lista[0].ultimoError").value("Connection refused"))
                .andExpect(jsonPath("$.cobrosAbiertos[0].id").value(2488))
                .andExpect(jsonPath("$.cobrosAbiertos[0].pasarela").value("mercadopago"));
    }

    @Test
    void liberar_un_cobro_anota_al_admin_de_la_sesion_y_dice_como_quedo() throws Exception {
        when(operacion.liberarCobro(2488L, "admin@databridge.cl"))
                .thenReturn(new OperacionResponse.CobroLiberado(2488L, "mercadopago", "expired"));

        mvc.perform(post("/api/admin/payments/cobros/2488/liberar").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("expired"));
        verify(operacion).liberarCobro(2488L, "admin@databridge.cl");
    }

    @Test
    void reintentar_un_aviso_y_cargar_la_uf_anotan_al_admin_de_la_sesion() throws Exception {
        when(operacion.cargarUf(LocalDate.parse("2026-10-27"), new BigDecimal("40123.45"), "admin@databridge.cl"))
                .thenReturn(new UfResponse(LocalDate.parse("2026-10-27"), new BigDecimal("40123.45"), "manual"));

        mvc.perform(post("/api/admin/payments/avisos/3/reintentar").header("Authorization", admin()))
                .andExpect(status().isOk());
        verify(operacion).reintentarAviso(3L, "admin@databridge.cl");

        mvc.perform(post("/api/admin/payments/uf").header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"dia\":\"2026-10-27\",\"valor\":\"40123.45\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fuente").value("manual"));
    }

    @Test
    void una_uf_sin_dia_o_que_no_es_positiva_se_rechaza_con_el_motivo() throws Exception {
        mvc.perform(post("/api/admin/payments/uf").header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"dia\":\"2026-10-27\",\"valor\":\"-1\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("La UF tiene que ser positiva"));
        mvc.perform(post("/api/admin/payments/uf").header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"valor\":\"40123.45\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(operacion);
    }

    @Test
    void ni_una_empresa_ni_un_deudor_tocan_la_operacion() throws Exception {
        for (String sesion : List.of(empresa(), deudor())) {
            mvc.perform(get("/api/admin/payments/operacion").header("Authorization", sesion))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/admin/payments/avisos/3/reintentar").header("Authorization", sesion))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/admin/payments/cobros/2488/liberar").header("Authorization", sesion))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/admin/payments/uf").header("Authorization", sesion)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"dia\":\"2026-10-27\",\"valor\":\"1\"}"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(operacion);
    }

    @Test
    void los_pagos_de_un_deudor_los_ve_solo_el_admin() throws Exception {
        when(pagosDelDeudor.pagos("16482337-7")).thenReturn(List.of(new PagoDeDeudorResponse(2494L, 15L,
                "76418902-7", Payment.Gateway.webpay, Payment.Status.paid, new BigDecimal("125000"),
                Payment.Currency.CLP, 125000L, 1, Instant.parse("2026-11-13T15:00:00Z"),
                Instant.parse("2026-11-13T15:02:00Z"))));

        mvc.perform(get("/api/admin/payments/deudores/16482337-7/pagos").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].pasarela").value("webpay"))
                .andExpect(jsonPath("$[0].cuotas").value(1));
        mvc.perform(get("/api/admin/payments/deudores/16482337-7/pagos").header("Authorization", empresa()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/payments/deudores/16482337-7/pagos").header("Authorization", deudor()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/payments/deudores/16482337-7/pagos")).andExpect(status().isUnauthorized());
        verify(pagosDelDeudor, times(1)).pagos(any());
    }

    @Test
    void los_pagos_de_todas_las_empresas_los_ve_y_los_verifica_solo_el_admin() throws Exception {
        PagoAdminResponse pago = new PagoAdminResponse(2494L, 15L, "76418902-7", Payment.Gateway.webpay,
                Payment.Status.created, new BigDecimal("125000"), Payment.Currency.CLP, 125000L, 1,
                Instant.parse("2026-11-18T12:00:00Z"), null, true, false);
        when(pagosAdmin.buscar(null, null, null, null, null, true, 0, 20))
                .thenReturn(new PageImpl<>(List.of(pago), PageRequest.of(0, 20), 1));
        when(pagosAdmin.detalle(2494L)).thenReturn(new PagoAdminDetalleResponse(pago, List.of()));
        when(pagosAdmin.verificar(2494L, "admin@databridge.cl")).thenReturn(new PagoVerificadoResponse(2494L,
                Payment.Gateway.webpay, Payment.Status.created, Payment.Status.created));

        mvc.perform(get("/api/admin/payments/pagos").param("revisar", "true").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.pagos[0].abiertoHaceMasDeUnaHora").value(true))
                .andExpect(jsonPath("$._embedded.pagos[0].deudorRut").doesNotExist())
                .andExpect(jsonPath("$.page.totalElements").value(1));
        mvc.perform(get("/api/admin/payments/pagos/2494").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pago.id").value(2494));
        mvc.perform(post("/api/admin/payments/pagos/2494/verificar").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ahora").value("created"));

        for (String otro : List.of(empresa(), deudor())) {
            mvc.perform(get("/api/admin/payments/pagos").header("Authorization", otro)).andExpect(status().isForbidden());
            mvc.perform(get("/api/admin/payments/pagos/2494").header("Authorization", otro))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/admin/payments/pagos/2494/verificar").header("Authorization", otro))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/admin/payments/pagos/2494/verificar")).andExpect(status().isUnauthorized());
        verify(pagosAdmin, times(1)).verificar(any(), any());
    }
}
