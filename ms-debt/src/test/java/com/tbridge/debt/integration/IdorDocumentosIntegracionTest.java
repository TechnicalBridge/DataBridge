package com.tbridge.debt.integration;

import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.DebtEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #364: un deudor autenticado no puede descargar el comprobante de pago
 * ni el certificado de deuda de otro deudor (IDOR/BOLA).
 *
 * <p>Las pruebas corren contra MySQL real con Flyway y crean dos deudores con
 * sus propias deudas y pagos. El token de A pide el documento de B: el servicio
 * debe responder 403 y no incluir ningun dato de B en el cuerpo.
 */
@EnabledIf("hayBaseDeDatos")
class IdorDocumentosIntegracionTest extends BaseIdorMySql {

    @Test
    void las_migraciones_estan_aplicadas_en_mysql_de_verdad() {
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class))
                .isGreaterThanOrEqualTo(13);
    }

    @Test
    void el_deudor_descarga_su_comprobante_pero_no_el_de_otro() throws Exception {
        Debt deudaA = crearDeuda("IDOR-A-" + sufijo, RUT_DEUDOR_A, "Deudor A", "deudor.a@correo.cl", 410000);
        Debt deudaB = crearDeuda("IDOR-B-" + sufijo, RUT_DEUDOR_B, "Deudor B", "deudor.b@correo.cl", 520000);
        DebtEvent pagoA = registrarPago(deudaA, "41000");
        DebtEvent pagoB = registrarPago(deudaB, "52000");
        String tokenA = tokenDeudor(RUT_DEUDOR_A);

        MvcResult propio = mvc.perform(get("/api/debts/pagos/" + pagoA.getId() + "/comprobante")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("comprobante-pago-" + pagoA.getId())))
                .andReturn();
        assertThat(propio.getResponse().getContentAsByteArray())
                .startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));

        MvcResult ajeno = mvc.perform(get("/api/debts/pagos/" + pagoB.getId() + "/comprobante")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isForbidden())
                .andReturn();
        assertThat(ajeno.getResponse().getContentAsString())
                .doesNotContain(RUT_DEUDOR_B)
                .doesNotContain("Deudor B")
                .doesNotContain("52000");
    }

    @Test
    void el_certificado_propio_se_emite_y_el_ajeno_no() throws Exception {
        Debt deudaA = marcarPagada(crearDeuda("IDOR-CERT-A-" + sufijo, RUT_DEUDOR_A,
                "Deudor A", "deudor.a@correo.cl", 410000));
        Debt deudaB = marcarPagada(crearDeuda("IDOR-CERT-B-" + sufijo, RUT_DEUDOR_B,
                "Deudor B", "deudor.b@correo.cl", 520000));
        String tokenA = tokenDeudor(RUT_DEUDOR_A);

        MvcResult propio = mvc.perform(get("/api/debts/" + deudaA.getId() + "/certificate")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("certificado")))
                .andReturn();
        assertThat(propio.getResponse().getContentAsByteArray())
                .startsWith("%PDF".getBytes(StandardCharsets.US_ASCII));

        MvcResult ajeno = mvc.perform(get("/api/debts/" + deudaB.getId() + "/certificate")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isForbidden())
                .andReturn();
        assertThat(ajeno.getResponse().getContentAsString())
                .doesNotContain(RUT_DEUDOR_B)
                .doesNotContain("Deudor B")
                .doesNotContain("520000");
    }

    @Test
    void el_historial_solo_muestra_los_pagos_del_deudor() throws Exception {
        Debt deudaA = crearDeuda("IDOR-LISTA-A-" + sufijo, RUT_DEUDOR_A, "Deudor A", "deudor.a@correo.cl", 410000);
        Debt deudaB = crearDeuda("IDOR-LISTA-B-" + sufijo, RUT_DEUDOR_B, "Deudor B", "deudor.b@correo.cl", 520000);
        DebtEvent pagoA = registrarPago(deudaA, "41000");
        DebtEvent pagoB = registrarPago(deudaB, "52000");

        mvc.perform(get("/api/debts/pagos").header("Authorization", "Bearer " + tokenDeudor(RUT_DEUDOR_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.pagos[*].id").value(hasItem(pagoA.getId().intValue())))
                .andExpect(jsonPath("$._embedded.pagos[*].id").value(not(hasItem(pagoB.getId().intValue()))));
    }

    @Test
    void un_comprobante_o_certificado_inexistente_es_404() throws Exception {
        String tokenA = tokenDeudor(RUT_DEUDOR_A);

        mvc.perform(get("/api/debts/pagos/999999999/comprobante")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/debts/999999999/certificate")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }
}
