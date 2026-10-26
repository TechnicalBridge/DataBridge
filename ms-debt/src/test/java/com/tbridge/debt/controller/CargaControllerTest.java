package com.tbridge.debt.controller;

import com.tbridge.debt.config.SecurityConfig;
import com.tbridge.debt.dto.response.CarteraResponse;
import com.tbridge.debt.dto.response.RevisionResponse;
import com.tbridge.debt.exception.CarteraInvalida;
import com.tbridge.debt.security.JwtTestIssuer;
import com.tbridge.debt.service.CargaService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** La carga y su revision, vistas desde el portal: lo que responden y como dicen lo que falta. */
@WebMvcTest(CargaController.class)
@Import({SecurityConfig.class, com.tbridge.debt.security.JwtTestConfig.class})
@ActiveProfiles("test")
class CargaControllerTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtTestIssuer jwt;
    @MockitoBean private CargaService cargas;

    private final MockMultipartFile planilla = new MockMultipartFile("archivo", "cartera.csv", "text/csv",
            "deuda_id;accion".getBytes(StandardCharsets.UTF_8));

    private String empresa() {
        return "Bearer " + jwt.issue("1", "persona@empresa.cl", "CREDITOR", "Persona", "76418902-7");
    }

    @Test
    void la_revision_dice_lo_que_pasaria_y_cuanto_entraria_sin_pedir_lote_ni_corte() throws Exception {
        when(cargas.revisar(any(), any(), isNull(), isNull(), eq("76418902-7"), eq("CMP-1"))).thenReturn(
                new RevisionResponse(new CarteraResponse("CARGA-1", false, 2, 1, 1, List.of(), List.of()),
                        List.of(new RevisionResponse.Monto("CLP", 1, new BigDecimal("1040000")))));

        mvc.perform(multipart("/api/debts/cartera/revision").file(planilla)
                        .param("acreedor_rut", "76418902-7").param("campana_id_externo", "CMP-1")
                        .header("Authorization", empresa()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cartera.aceptadas").value(1))
                .andExpect(jsonPath("$.cartera.rechazadas").value(1))
                .andExpect(jsonPath("$.entrarian[0].moneda").value("CLP"))
                .andExpect(jsonPath("$.entrarian[0].deudas").value(1))
                .andExpect(jsonPath("$.entrarian[0].monto").value(1040000));
    }

    @Test
    void la_plantilla_se_descarga_como_un_excel() throws Exception {
        when(cargas.plantilla(any())).thenReturn(new byte[]{'P', 'K', 3, 4});

        mvc.perform(get("/api/debts/cartera/plantilla").header("Authorization", empresa()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string("Content-Disposition",
                        "attachment; filename=\"cartera-plantilla.xlsx\""))
                .andExpect(content().bytes(new byte[]{'P', 'K', 3, 4}));
        mvc.perform(get("/api/debts/cartera/plantilla")).andExpect(status().isUnauthorized());
    }

    @Test
    void los_problemas_de_una_planilla_de_excel_vienen_uno_por_uno() throws Exception {
        when(cargas.revisar(any(), any(), any(), any(), any(), any())).thenThrow(new CarteraInvalida("excel_invalido",
                "La planilla tiene 2 problemas. Corrígelos y vuelve a revisarla", 400, List.of(
                        "Hoja Deudas, fila 2, columna RUT del deudor: el RUT 11111111-1 no está en la hoja Deudores",
                        "Hoja Deudas, fila 3, columna Monto: falta el monto")));

        mvc.perform(multipart("/api/debts/cartera/revision").file(planilla).param("acreedor_rut", "76418902-7")
                        .param("campana_id_externo", "CMP-1").header("Authorization", empresa()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.codigo").value("excel_invalido"))
                .andExpect(jsonPath("$.error.detalles.length()").value(2))
                .andExpect(jsonPath("$.error.detalles[1]").value("Hoja Deudas, fila 3, columna Monto: falta el monto"));
    }

    @Test
    void sin_campana_la_carga_responde_400_con_su_codigo() throws Exception {
        when(cargas.subir(any(), any(), any(), any(), any(), any())).thenThrow(new CarteraInvalida("sin_campana",
                "Toda carga entra en una campaña: elige una o crea una nueva", 400));

        mvc.perform(multipart("/api/debts/cartera").file(planilla).param("acreedor_rut", "76418902-7")
                        .header("Authorization", empresa()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.codigo").value("sin_campana"))
                .andExpect(jsonPath("$.error.mensaje").value("Toda carga entra en una campaña: elige una o crea una nueva"))
                //  Sin varios problemas, no hay lista: la forma del contrato no cambia.
                .andExpect(jsonPath("$.error.detalles").doesNotExist());
    }
}
