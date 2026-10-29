package com.tbridge.payments.controller;

import com.tbridge.payments.security.JwtTestIssuer;
import com.tbridge.payments.config.SecurityConfig;
import com.tbridge.payments.dto.response.UfResponse;
import com.tbridge.payments.service.UfLoader;
import com.tbridge.payments.service.UfService;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** La carga de la UF a mano, detras de la clave interna. */
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@WebMvcTest(InternalController.class)
@Import({SecurityConfig.class, com.tbridge.payments.security.JwtTestConfig.class})
@ActiveProfiles("test")
class InternalControllerTest {

    private static final String CLAVE = "clave-interna-de-prueba";

    @Autowired private MockMvc mvc;
    @MockitoBean private UfService uf;
    @MockitoBean private UfLoader cargador;

    @Test
    void sin_la_clave_nadie_cambia_la_uf() throws Exception {
        mvc.perform(post("/internal/uf").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dia\":\"2026-09-24\",\"valor\":\"39876.54\"}"))
                .andExpect(status().isUnauthorized());
        verify(uf, never()).cargarAMano(any(), any());
    }

    @Test
    void con_la_clave_se_guarda_el_valor_del_dia() throws Exception {
        when(uf.cargarAMano(LocalDate.of(2026, 9, 24), new BigDecimal("39876.54")))
                .thenReturn(new UfResponse(LocalDate.of(2026, 9, 24), new BigDecimal("39876.54"), "manual"));

        mvc.perform(post("/internal/uf").header("X-Internal-Key", CLAVE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dia\":\"2026-09-24\",\"valor\":\"39876.54\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dia").value("2026-09-24"))
                .andExpect(jsonPath("$.fuente").value("manual"));
    }

    @Test
    void una_fecha_que_no_es_fecha_o_una_uf_negativa_es_400() throws Exception {
        mvc.perform(post("/internal/uf").header("X-Internal-Key", CLAVE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dia\":\"ayer\",\"valor\":\"39876.54\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/internal/uf").header("X-Internal-Key", CLAVE).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"dia\":\"2026-09-24\",\"valor\":\"-5\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("La UF tiene que ser positiva"));
    }
}
