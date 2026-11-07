package com.tbridge.gateway.integration;

import com.tbridge.gateway.filter.RetornoDePasarela;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue 335 - gateway: prueba el filtro RetornoDePasarela (quita Origin en la vuelta de Webpay).
 */
class RetornoDePasarelaTest {

    @Test
    void orden_es_mas_alto_posible() {
        RetornoDePasarela filtro = new RetornoDePasarela();
        assertEquals(org.springframework.core.Ordered.HIGHEST_PRECEDENCE, filtro.getOrder());
    }

    @Test
    void ruta_es_la_de_retorno_de_webpay() {
        assertEquals("/api/payments/public/webpay/retorno", RetornoDePasarelaTest.RUTA);
    }

    static final String RUTA = "/api/payments/public/webpay/retorno";
}
