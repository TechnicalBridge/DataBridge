package com.tbridge.payments.security;

import com.tbridge.payments.integration.BasePagosMySql;
import com.tbridge.payments.model.Payment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #364: el token del deudor A no da acceso a los pagos del deudor B en
 * ms-payments. Cubre el filtro JWT y la regla de pertenencia por RUT.
 */
@EnabledIf("hayBaseDeDatos")
class IdorPagosSeguridadTest extends BasePagosMySql {

    @Test
    void sin_sesion_los_pagos_son_401() throws Exception {
        mvc.perform(get("/api/payments/1")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/payments/1/historia")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/payments")).andExpect(status().isUnauthorized());
    }

    @Test
    void un_token_sin_firma_es_401() throws Exception {
        String sinFirma = JwtTestTokens.token(JwtTestTokens.claims(), JwtTestTokens.PAIR,
                JwtTestTokens.publicKey(JwtTestTokens.PAIR).getKeyID(), "none");

        mvc.perform(get("/api/payments/1").header("Authorization", "Bearer " + sinFirma))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/payments/1/historia").header("Authorization", "Bearer " + sinFirma))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void un_token_firmado_con_otra_llave_es_401() throws Exception {
        var otraLlave = JwtTestTokens.pair("secp256r1");
        String firmadoConOtra = JwtTestTokens.token(JwtTestTokens.claims(), otraLlave,
                JwtTestTokens.publicKey(JwtTestTokens.PAIR).getKeyID(), "ES256");

        mvc.perform(get("/api/payments/1").header("Authorization", "Bearer " + firmadoConOtra))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/payments/1/historia").header("Authorization", "Bearer " + firmadoConOtra))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void el_deudor_no_accede_al_pago_de_otro() throws Exception {
        Payment pagoB = crearPago(RUT_DEUDOR_B, RUT_ACREEDOR, 520000);
        String tokenA = tokenDeudor(RUT_DEUDOR_A);

        mvc.perform(get("/api/payments/" + pagoB.getId()).header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/payments/" + pagoB.getId() + "/historia")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isForbidden());
    }
}
