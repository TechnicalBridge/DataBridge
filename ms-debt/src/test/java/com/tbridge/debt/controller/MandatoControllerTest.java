package com.tbridge.debt.controller;

import com.tbridge.debt.config.SecurityConfig;
import com.tbridge.debt.dto.request.MandatoPortalRequest;
import com.tbridge.debt.dto.response.MandatoPortalResponse;
import com.tbridge.debt.dto.response.MandatosResponse;
import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.security.JwtTestIssuer;
import com.tbridge.debt.service.DebtService;
import com.tbridge.debt.service.MandatoPortalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
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

/** Los mandatos desde el portal (#234): solo una empresa, y la empresa sale de la sesion, no del pedido. */
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@WebMvcTest(MandatoController.class)
@Import({SecurityConfig.class, com.tbridge.debt.security.JwtTestConfig.class})
@ActiveProfiles("test")
class MandatoControllerTest {

    private static final String CAMILA = "camila.reyes@apofyx.cl";

    @Autowired private MockMvc mvc;
    @Autowired private JwtTestIssuer jwt;
    @MockitoBean private MandatoPortalService mandatos;
    @MockitoBean private DebtService debts;

    private final Organization apofyx = new Organization();

    @BeforeEach
    void preparar() {
        apofyx.setId(2L);
        apofyx.setRut("77305118-6");
        when(debts.organizacionDe(any())).thenReturn(apofyx);
    }

    private String empresa() {
        return "Bearer " + jwt.issue("1", CAMILA, "CREDITOR", "Camila Reyes", "77305118-6");
    }

    private String deudor() {
        return "Bearer " + jwt.issue("16482337-7", null, "DEBTOR", null, "16482337-7");
    }

    private static MandatoPortalResponse mandato(String estado) {
        return new MandatoPortalResponse(4L, "76418902-7", "Patrimonio Inmuebles", LocalDate.of(2026, 10, 1), null,
                120, null, estado, CAMILA, Instant.parse("2026-10-29T12:00:00Z"), null, null, null, null, 12);
    }

    @Test
    void sin_sesion_o_como_deudor_no_se_ven_ni_se_declaran() throws Exception {
        mvc.perform(get("/api/debts/mandatos")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/debts/mandatos").header("Authorization", deudor())).andExpect(status().isForbidden());
        mvc.perform(post("/api/debts/mandatos").header("Authorization", deudor())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"acreedorRut\":\"76418902-7\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/debts/mandatos/4/terminar").header("Authorization", deudor()))
                .andExpect(status().isForbidden());
        verify(mandatos, never()).declarar(any(), any(), any());
        verify(mandatos, never()).terminar(any(), any(), any());
    }

    @Test
    void la_agencia_ve_sus_mandatos() throws Exception {
        when(mandatos.listar(apofyx)).thenReturn(new MandatosResponse(true, List.of(mandato("vigente"))));

        mvc.perform(get("/api/debts/mandatos").header("Authorization", empresa()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agencia").value(true))
                .andExpect(jsonPath("$.mandatos[0].acreedor").value("Patrimonio Inmuebles"))
                .andExpect(jsonPath("$.mandatos[0].desde").value("2026-10-01"))
                .andExpect(jsonPath("$.mandatos[0].estado").value("vigente"))
                .andExpect(jsonPath("$.mandatos[0].deudasEnGestion").value(12));
    }

    @Test
    void declarar_responde_201_y_queda_a_nombre_de_quien_tiene_la_sesion() throws Exception {
        when(mandatos.declarar(eq(apofyx), eq(CAMILA), any())).thenReturn(mandato("vigente"));

        mvc.perform(post("/api/debts/mandatos").header("Authorization", empresa())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"acreedorRut\":\"76.418.902-7\",\"moraMaximaDias\":90,\"descuentoMaximoMora\":50}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(4));

        ArgumentCaptor<MandatoPortalRequest> pedido = ArgumentCaptor.forClass(MandatoPortalRequest.class);
        verify(mandatos).declarar(eq(apofyx), eq(CAMILA), pedido.capture());
        assertEquals("76.418.902-7", pedido.getValue().acreedorRut());
        assertEquals(90, pedido.getValue().moraMaximaDias());
    }

    @Test
    void sin_rut_del_acreedor_no_llega_al_servicio() throws Exception {
        mvc.perform(post("/api/debts/mandatos").header("Authorization", empresa())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"razonSocial\":\"Patrimonio\"}"))
                .andExpect(status().isBadRequest());
        verify(mandatos, never()).declarar(any(), any(), any());
    }

    @Test
    void renovar_y_terminar_pasan_el_id_y_quien_lo_hizo() throws Exception {
        when(mandatos.renovar(eq(apofyx), eq(CAMILA), eq(4L), any())).thenReturn(mandato("vigente"));
        when(mandatos.terminar(apofyx, CAMILA, 4L)).thenReturn(mandato("terminado"));

        mvc.perform(post("/api/debts/mandatos/4/renovar").header("Authorization", empresa())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"hasta\":\"2027-09-30\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/debts/mandatos/4/terminar").header("Authorization", empresa()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("terminado"));
    }

    @Test
    void lo_que_rechaza_el_servicio_llega_con_su_estado_y_su_mensaje() throws Exception {
        when(mandatos.terminar(apofyx, CAMILA, 4L))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "Ese mandato ya estaba terminado"));

        mvc.perform(post("/api/debts/mandatos/4/terminar").header("Authorization", empresa()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Ese mandato ya estaba terminado"));
    }
}
