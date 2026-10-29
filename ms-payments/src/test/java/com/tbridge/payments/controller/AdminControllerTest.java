package com.tbridge.payments.controller;

import com.tbridge.payments.config.SecurityConfig;
import com.tbridge.payments.dto.response.OperacionResponse;
import com.tbridge.payments.dto.response.ReglasResponse;
import com.tbridge.payments.dto.response.UfResponse;
import com.tbridge.payments.security.JwtTestIssuer;
import com.tbridge.payments.service.OperacionService;
import com.tbridge.payments.service.ReglasService;
import org.junit.jupiter.api.Test;
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

import static org.mockito.ArgumentMatchers.any;
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

    // ------------------------------------------------------------------
    //  La operacion de los pagos (#139)
    // ------------------------------------------------------------------

    @Test
    void el_admin_ve_la_operacion_de_los_pagos() throws Exception {
        when(operacion.operacion()).thenReturn(new OperacionResponse(
                new OperacionResponse.Pagos(3, 1, 4, 1),
                new OperacionResponse.Uf(LocalDate.parse("2026-10-27"), false, null, null),
                new OperacionResponse.Avisos(0, 1, List.of(new OperacionResponse.Aviso(3L, 58L, "failed", 6, null,
                        "Connection refused"))), List.of()));

        mvc.perform(get("/api/admin/payments/operacion").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pagos.abiertos").value(3))
                .andExpect(jsonPath("$.pagos.duplicados").value(1))
                .andExpect(jsonPath("$.uf.cargada").value(false))
                .andExpect(jsonPath("$.avisos.lista[0].pago").value(58))
                .andExpect(jsonPath("$.avisos.lista[0].ultimoError").value("Connection refused"));
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
            mvc.perform(post("/api/admin/payments/uf").header("Authorization", sesion)
                            .contentType(MediaType.APPLICATION_JSON).content("{\"dia\":\"2026-10-27\",\"valor\":\"1\"}"))
                    .andExpect(status().isForbidden());
        }
        verifyNoInteractions(operacion);
    }
}
