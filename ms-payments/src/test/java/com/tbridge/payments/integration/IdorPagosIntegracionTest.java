package com.tbridge.payments.integration;

import com.tbridge.payments.model.Payment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #364: un deudor autenticado no puede consultar el pago ni el libro de
 * otro deudor en ms-payments (IDOR/BOLA). El RUT del JWT es la unica fuente de
 * pertenencia; el id de la URL no autoriza nada.
 */
@EnabledIf("hayBaseDeDatos")
class IdorPagosIntegracionTest extends BasePagosMySql {

    @Test
    void las_migraciones_estan_aplicadas_en_mysql_de_verdad() {
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class))
                .isGreaterThanOrEqualTo(6);
    }

    @Test
    void el_deudor_ve_su_pago_pero_no_el_de_otro() throws Exception {
        Payment pagoA = crearPago(RUT_DEUDOR_A, RUT_ACREEDOR, 410000);
        Payment pagoB = crearPago(RUT_DEUDOR_B, RUT_ACREEDOR, 520000);
        String tokenA = tokenDeudor(RUT_DEUDOR_A);

        mvc.perform(get("/api/payments/" + pagoA.getId()).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(pagoA.getId()));

        MvcResult ajeno = mvc.perform(get("/api/payments/" + pagoB.getId())
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isForbidden())
                .andReturn();
        assertThat(ajeno.getResponse().getContentAsString())
                .doesNotContain(RUT_DEUDOR_B)
                .doesNotContain("520000");
    }

    @Test
    void el_deudor_ve_su_libro_pero_no_el_del_otro() throws Exception {
        Payment pagoA = crearPago(RUT_DEUDOR_A, RUT_ACREEDOR, 410000);
        Payment pagoB = crearPago(RUT_DEUDOR_B, RUT_ACREEDOR, 520000);
        String tokenA = tokenDeudor(RUT_DEUDOR_A);

        mvc.perform(get("/api/payments/" + pagoA.getId() + "/historia")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isOk());

        mvc.perform(get("/api/payments/" + pagoB.getId() + "/historia")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isForbidden());
    }

    @Test
    void la_lista_solo_trae_los_pagos_del_deudor() throws Exception {
        Payment pagoA = crearPago(RUT_DEUDOR_A, RUT_ACREEDOR, 410000);
        Payment pagoB = crearPago(RUT_DEUDOR_B, RUT_ACREEDOR, 520000);

        mvc.perform(get("/api/payments").header("Authorization", "Bearer " + tokenDeudor(RUT_DEUDOR_A)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.payments[*].id").value(hasItem(pagoA.getId().intValue())))
                .andExpect(jsonPath("$._embedded.payments[*].id").value(not(hasItem(pagoB.getId().intValue()))));
    }

    @Test
    void un_pago_inexistente_es_404() throws Exception {
        String tokenA = tokenDeudor(RUT_DEUDOR_A);

        mvc.perform(get("/api/payments/999999999").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/payments/999999999/historia").header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isNotFound());
    }
}
