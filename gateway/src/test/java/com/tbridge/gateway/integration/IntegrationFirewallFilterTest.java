package com.tbridge.gateway.integration;

import com.tbridge.gateway.filter.IntegrationFirewallFilter;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue 335 - gateway: prueba el filtro IntegrationFirewallFilter (firewall por dominio/CIDR).
 */
@SpringBootTest
@ActiveProfiles("test")
class IntegrationFirewallFilterTest {

    @Test
    void configuracion_vacia_no_bloquea_nada() {
        IntegrationFirewallFilter filtro = new IntegrationFirewallFilter("", "127\\.0\\.0\\.1");
        assertEquals(-3, filtro.getOrder());
    }

    @Test
    void hash_de_api_key_es_sha256() {
        String h = IntegrationFirewallFilter.hash("clave");
        assertEquals(64, h.length());
        assertTrue(h.matches("[a-f0-9]{64}"));
    }
}
