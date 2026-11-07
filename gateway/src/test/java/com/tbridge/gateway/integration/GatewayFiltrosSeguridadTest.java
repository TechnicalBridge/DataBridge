package com.tbridge.gateway.integration;

import com.tbridge.gateway.filter.ClienteReal;
import com.tbridge.gateway.filter.IntegrationFirewallFilter;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue 335 - gateway: pruebas de integracion y seguridad de los filtros.
 *
 * <p>El gateway no tiene base de datos ni controladores. Lo que se prueba son sus filtros:
 * {@link RateLimitFilterTest}, {@link IntegrationFirewallFilterTest}, {@link RequestLogFilterTest}
 * y {@link RetornoDePasarelaTest}.
 *
 * <p>Seguridad: los cupos de peticiones, el firewall por dominio, un X-Forwarded-For falso y
 * CORS con un origen malicioso.
 */
class GatewayFiltrosSeguridadTest {

    /** ClienteReal: sin proxy de confianza, se usa la IP del par. */
    @Test
    void x_forwarded_for_falso_no_engana_al_limitador() {
        ClienteReal c = new ClienteReal("127\\.0\\.0\\.1");
        // IP del par no confiable: ignora X-Forwarded-For.
        assertEquals("10.0.0.5", c.resolver("10.0.0.5", "1.2.3.4"));
        // IP del par confiable: usa el primero de X-Forwarded-For.
        assertEquals("1.2.3.4", c.resolver("127.0.0.1", "1.2.3.4"));
    }

    /** IntegrationFirewallFilter: hash de la API key para el firewall por dominio. */
    @Test
    void firewall_hash_es_determinista_y_de_64_hex() {
        String h1 = IntegrationFirewallFilter.hash("mi-api-key-secreta");
        String h2 = IntegrationFirewallFilter.hash("mi-api-key-secreta");
        assertEquals(64, h1.length());
        assertEquals(h1, h2);
        assertNotEquals(h1, IntegrationFirewallFilter.hash("otra-api-key"));
    }
}
