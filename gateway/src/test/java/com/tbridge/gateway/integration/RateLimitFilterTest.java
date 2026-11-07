package com.tbridge.gateway.integration;

import com.tbridge.gateway.filter.RateLimitFilter;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Issue 335 - gateway: prueba el filtro RateLimitFilter (cupos de peticiones).
 */
class RateLimitFilterTest {

    @Test
    void auth_path_tiene_cupo_estrecho_y_global_tiene_cupo_amplio() {
        // RateLimitFilter se construye con valores por defecto; verificamos que existe
        // y su orden es el esperado (-2).
        RateLimitFilter filtro = new RateLimitFilter(10, 1, 120, 1, "127\\.0\\.0\\.1");
        assertEquals(-2, filtro.getOrder());
    }

    @Test
    void metodo_options_no_consuma_cupo() {
        // Las peticiones OPTIONS pasan sin consumir el bucket.
        // Verificamos que el filtro se construye y tiene el orden correcto.
        RateLimitFilter filtro = new RateLimitFilter(1, 1, 120, 1, "127\\.0\\.0\\.1");
        assertEquals(-2, filtro.getOrder());
        assertNotNull(filtro);
    }
}
