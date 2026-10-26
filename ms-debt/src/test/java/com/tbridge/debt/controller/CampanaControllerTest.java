package com.tbridge.debt.controller;

import com.tbridge.debt.config.SecurityConfig;
import com.tbridge.debt.dto.request.CampanaRequest;
import com.tbridge.debt.dto.response.CampanaPortalResponse;
import com.tbridge.debt.exception.CarteraInvalida;
import com.tbridge.debt.exception.CarteraInvalidaHandler;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.security.JwtTestIssuer;
import com.tbridge.debt.service.CampanaService;
import com.tbridge.debt.service.DebtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Las campanas desde el portal: solo una empresa, solo las que gestiona, y con las reglas del contrato. */
@WebMvcTest(CampanaController.class)
@Import({SecurityConfig.class, com.tbridge.debt.security.JwtTestConfig.class, CarteraInvalidaHandler.class})
@ActiveProfiles("test")
class CampanaControllerTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtTestIssuer jwt;
    @MockitoBean private CampanaService campanas;
    @MockitoBean private DebtService debts;

    private final Organization apofyx = new Organization();

    @BeforeEach
    void preparar() {
        apofyx.setId(2L);
        apofyx.setRut("77305118-6");
        when(debts.organizacionDe(any())).thenReturn(apofyx);
    }

    private String empresa() {
        return "Bearer " + jwt.issue("1", "camila.reyes@apofyx.cl", "CREDITOR", "Camila Reyes", "77305118-6");
    }

    private String deudor() {
        return "Bearer " + jwt.issue("16482337-7", null, "DEBTOR", null, "16482337-7");
    }

    private static CampanaPortalResponse campana(String estado) {
        return new CampanaPortalResponse("APX-CMP-8", "Arriendos octubre", "76418902-7", "Patrimonio Inmuebles", false,
                LocalDate.of(2026, 10, 1), null, List.of("correo"), 3, List.of(1, 4, 11), estado, 12, 20, 4, 3);
    }

    @Test
    void sin_sesion_o_como_deudor_no_se_ven_ni_se_cambian() throws Exception {
        mvc.perform(get("/api/debts/campanas")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/debts/campanas").header("Authorization", deudor())).andExpect(status().isForbidden());
        mvc.perform(post("/api/debts/campanas/APX-CMP-8/estado").header("Authorization", deudor())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"estado\":\"pausada\"}"))
                .andExpect(status().isForbidden());
        verify(campanas, never()).cambiarEstado(any(), any(), any());
    }

    @Test
    void la_lista_ofrece_lo_que_se_puede_hacer_segun_el_estado() throws Exception {
        when(campanas.listar(apofyx)).thenReturn(List.of(campana("en_curso")));

        mvc.perform(get("/api/debts/campanas").header("Authorization", empresa()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.campanas[0].idExterno").value("APX-CMP-8"))
                .andExpect(jsonPath("$._embedded.campanas[0].cadenciaDias[2]").value(11))
                .andExpect(jsonPath("$._embedded.campanas[0]._links.pausar.href").exists())
                .andExpect(jsonPath("$._embedded.campanas[0]._links.reanudar").doesNotExist());
    }

    @Test
    void una_terminada_ya_no_ofrece_cambiarse() throws Exception {
        when(campanas.listar(apofyx)).thenReturn(List.of(campana("terminada")));

        mvc.perform(get("/api/debts/campanas").header("Authorization", empresa()))
                .andExpect(jsonPath("$._embedded.campanas[0]._links.pausar").doesNotExist())
                .andExpect(jsonPath("$._embedded.campanas[0]._links.terminar").doesNotExist());
    }

    @Test
    void guardar_lee_los_campos_del_contrato() throws Exception {
        when(campanas.guardar(eq(apofyx), any())).thenReturn(campana("en_curso"));

        mvc.perform(post("/api/debts/campanas").header("Authorization", empresa())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"acreedor_rut\":\"76418902-7\",\"nombre\":\"Arriendos octubre\",\"inicio\":\"2026-10-01\","
                                + "\"intentos\":3,\"cadencia_dias\":[1,4,11],\"canales\":[\"correo\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("en_curso"));

        ArgumentCaptor<CampanaRequest> pedido = ArgumentCaptor.forClass(CampanaRequest.class);
        verify(campanas).guardar(eq(apofyx), pedido.capture());
        assertEquals("76418902-7", pedido.getValue().acreedorRut());
        assertEquals(3, pedido.getValue().intentos());
        assertEquals("[1,4,11]", pedido.getValue().cadenciaDias().toString());
    }

    @Test
    void una_regla_que_no_se_cumple_responde_con_su_codigo() throws Exception {
        when(campanas.guardar(eq(apofyx), any())).thenThrow(
                new CarteraInvalida("cadencia_invalida", "La cadencia son dias crecientes y mayores que cero"));

        mvc.perform(post("/api/debts/campanas").header("Authorization", empresa())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cadencia_dias\":[4,1]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.codigo").value("cadencia_invalida"));
    }

    @Test
    void el_estado_es_obligatorio() throws Exception {
        mvc.perform(post("/api/debts/campanas/APX-CMP-8/estado").header("Authorization", empresa())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        verify(campanas, never()).cambiarEstado(any(), any(), any());
    }
}
