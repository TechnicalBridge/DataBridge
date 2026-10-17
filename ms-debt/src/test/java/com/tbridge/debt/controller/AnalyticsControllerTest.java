package com.tbridge.debt.controller;

import com.tbridge.debt.dto.response.ComisionResponse;
import com.tbridge.debt.service.ComisionService;
import static org.mockito.ArgumentMatchers.eq;
import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.security.JwtService;
import com.tbridge.debt.config.SecurityConfig;
import com.tbridge.debt.dto.response.ResumenResponse;
import com.tbridge.debt.service.AnalyticsService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AnalyticsController.class)
@Import({SecurityConfig.class, JwtService.class})
@ActiveProfiles("test")
class AnalyticsControllerTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @MockitoBean private AnalyticsService analytics;
    @MockitoBean private ComisionService comisiones;

    @Test
    void la_empresa_ve_el_resumen_de_su_cartera() throws Exception {
        when(analytics.resumenPara(any())).thenReturn(new ResumenResponse("APOFYX", "77305118-6", 3, 2, 1, 1, 0,
                List.of(new ResumenResponse.PorMoneda("CLP", new BigDecimal("1040000"), new BigDecimal("410000"),
                        new BigDecimal("28.3"))),
                List.of(), Map.of()));

        mvc.perform(get("/api/analytics/summary")
                        .header("Authorization", "Bearer " + jwt.issue("1", "c@apofyx.cl", "CREDITOR", "Camila", "77305118-6")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.organizacion").value("APOFYX"))
                .andExpect(jsonPath("$.porMoneda[0].tasaRecuperacion").value(28.3))
                .andExpect(jsonPath("$._links.deudas.href").value("http://localhost/api/debts"));
    }

    @Test
    void la_empresa_ve_su_comision_del_mes_con_numeros_y_una_fecha() throws Exception {
        when(comisiones.deLaEmpresa(any(), eq(java.time.YearMonth.of(2026, 10)))).thenReturn(new ComisionResponse(
                java.time.LocalDate.of(2026, 10, 1), 1_420_000, 4, new java.math.BigDecimal("10"), 142_000, 1_278_000,
                java.util.List.of(new ComisionResponse.PorCampana("CMP-1", "Arriendos octubre", 1_420_000, 4)),
                java.util.List.of(new ComisionResponse.TramoResponse(1_000_000, 5_000_000L, new java.math.BigDecimal("10"), true))));

        mvc.perform(get("/api/analytics/comision").param("mes", "2026-10")
                        .header("Authorization", "Bearer " + jwt.issue("1", "c@apofyx.cl", "CREDITOR", "Camila", "77305118-6")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mes").value("2026-10-01"))
                .andExpect(jsonPath("$.recuperado").value(1420000))
                .andExpect(jsonPath("$.porcentaje").value(10))
                .andExpect(jsonPath("$.comision").value(142000))
                .andExpect(jsonPath("$.paraLaEmpresa").value(1278000))
                .andExpect(jsonPath("$.porCampana[0].idExterno").value("CMP-1"))
                .andExpect(jsonPath("$.tramos[0].vigente").value(true));
        mvc.perform(get("/api/analytics/comision").param("mes", "octubre")
                        .header("Authorization", "Bearer " + jwt.issue("1", "c@apofyx.cl", "CREDITOR", "Camila", "77305118-6")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void un_deudor_no_ve_el_resumen_de_nadie() throws Exception {
        when(analytics.resumenPara(any())).thenThrow(new ApiException(HttpStatus.FORBIDDEN, "Solo el acreedor ve el resumen"));

        mvc.perform(get("/api/analytics/summary")
                        .header("Authorization", "Bearer " + jwt.issue("16482337-7", null, "DEBTOR", null, "16482337-7")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("Solo el acreedor ve el resumen"));
    }
}
