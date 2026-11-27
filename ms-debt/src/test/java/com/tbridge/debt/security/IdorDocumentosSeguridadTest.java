package com.tbridge.debt.security;

import com.tbridge.debt.integration.BaseIdorMySql;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.DebtEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Issue #364: la sesion del deudor A no sirve para ver documentos del deudor B.
 *
 * <p>Comprueba el filtro de seguridad (sin token, token sin firma y token con
 * otra llave) y la regla de pertenencia del servicio (403), sin filtrar datos
 * personales en la respuesta.
 */
@EnabledIf("hayBaseDeDatos")
class IdorDocumentosSeguridadTest extends BaseIdorMySql {

    @Test
    void sin_sesion_los_documentos_son_401() throws Exception {
        mvc.perform(get("/api/debts/pagos/1/comprobante"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/debts/1/certificate"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void un_token_sin_firma_es_401() throws Exception {
        String sinFirma = JwtTestTokens.token(JwtTestTokens.claims(), JwtTestTokens.PAIR,
                JwtTestTokens.publicKey(JwtTestTokens.PAIR).getKeyID(), "none");

        mvc.perform(get("/api/debts/pagos/1/comprobante")
                        .header("Authorization", "Bearer " + sinFirma))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/debts/1/certificate")
                        .header("Authorization", "Bearer " + sinFirma))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void un_token_firmado_con_otra_llave_es_401() throws Exception {
        var otraLlave = JwtTestTokens.pair("secp256r1");
        String firmadoConOtra = JwtTestTokens.token(JwtTestTokens.claims(), otraLlave,
                JwtTestTokens.publicKey(JwtTestTokens.PAIR).getKeyID(), "ES256");

        mvc.perform(get("/api/debts/pagos/1/comprobante")
                        .header("Authorization", "Bearer " + firmadoConOtra))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/debts/1/certificate")
                        .header("Authorization", "Bearer " + firmadoConOtra))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void el_deudor_no_accede_a_los_documentos_de_otro() throws Exception {
        //  A existe en la base con una deuda propia: sin ella, el servicio ni
        //  siquiera resuelve al deudor y responde 404 en vez de 403.
        marcarPagada(crearDeuda("IDOR-SEC-A-" + sufijo, RUT_DEUDOR_A,
                "Deudor A", "deudor.a@correo.cl", 410000));
        Debt deudaB = marcarPagada(crearDeuda("IDOR-SEC-B-" + sufijo, RUT_DEUDOR_B,
                "Deudor B", "deudor.b@correo.cl", 520000));
        DebtEvent pagoB = registrarPago(deudaB, "52000");
        String tokenA = tokenDeudor(RUT_DEUDOR_A);

        mvc.perform(get("/api/debts/pagos/" + pagoB.getId() + "/comprobante")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/debts/" + deudaB.getId() + "/certificate")
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isForbidden());
    }

    /**
     * Un PDF se descarga pidiendo {@code Accept: application/pdf}. Ese mismo
     * Accept no puede convertir el 403 en 500 ni en 406: el cuerpo del error
     * viaja igual en JSON, sin datos del otro deudor.
     */
    @Test
    void el_documento_ajeno_responde_403_aunque_el_cliente_pida_pdf() throws Exception {
        marcarPagada(crearDeuda("IDOR-PDF-A-" + sufijo, RUT_DEUDOR_A,
                "Deudor A", "deudor.a@correo.cl", 410000));
        Debt deudaB = marcarPagada(crearDeuda("IDOR-PDF-B-" + sufijo, RUT_DEUDOR_B,
                "Deudor B", "deudor.b@correo.cl", 520000));
        DebtEvent pagoB = registrarPago(deudaB, "52000");
        String tokenA = tokenDeudor(RUT_DEUDOR_A);

        mvc.perform(get("/api/debts/pagos/" + pagoB.getId() + "/comprobante")
                        .accept(MediaType.APPLICATION_PDF)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("No puedes ver esta deuda"));

        mvc.perform(get("/api/debts/" + deudaB.getId() + "/certificate")
                        .accept(MediaType.APPLICATION_PDF)
                        .header("Authorization", "Bearer " + tokenA))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.error").value("No puedes ver esta deuda"));
    }
}
