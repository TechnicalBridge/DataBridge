package com.tbridge.gateway.filter;

import com.github.benmanes.caffeine.cache.Cache;
import io.github.bucket4j.Bucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;

/**
 * El limitador: un cupo estrecho para lo que se puede adivinar (el codigo de
 * acceso) y uno general para todo lo demas, contado por cliente.
 */
@ExtendWith(MockitoExtension.class)
class RateLimitFilterTest {

    private static final String CONFIABLES = "127\\.0\\.0\\.1|172\\.(1[6-9]|2[0-9]|3[01])\\..*";

    @Mock
    private GatewayFilterChain cadena;

    private RateLimitFilter limitador;

    @BeforeEach
    void preparar() {
        //  2 intentos de acceso y 3 peticiones generales por minuto.
        limitador = new RateLimitFilter(2, 1, 3, 1, CONFIABLES);
        lenient().when(cadena.filter(any())).thenReturn(Mono.empty());
    }

    private HttpStatus pedir(String metodo, String ruta, String ip) {
        MockServerWebExchange intercambio = MockServerWebExchange.from(MockServerHttpRequest
                .method(org.springframework.http.HttpMethod.valueOf(metodo), ruta)
                .remoteAddress(new InetSocketAddress(ip, 50000)));
        limitador.filter(intercambio, cadena).block();
        return (HttpStatus) intercambio.getResponse().getStatusCode();
    }

    @Test
    void el_codigo_de_acceso_tiene_su_propio_cupo_estrecho() {
        assertNull(pedir("POST", "/api/auth/acceso", "203.0.113.7"));
        assertNull(pedir("POST", "/api/auth/acceso", "203.0.113.7"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, pedir("POST", "/api/auth/acceso", "203.0.113.7"));

        //  Agotarlo no deja a esa persona sin el resto del portal.
        assertNull(pedir("GET", "/api/debts", "203.0.113.7"));
    }

    @Test
    void cada_cliente_cuenta_por_separado() {
        for (int i = 0; i < 3; i++) {
            assertNull(pedir("GET", "/api/debts", "203.0.113.7"));
        }
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, pedir("GET", "/api/debts", "203.0.113.7"));
        assertNull(pedir("GET", "/api/debts", "198.51.100.4"));
    }

    @Test
    void renovar_la_sesion_no_gasta_el_cupo_del_codigo() {
        //  Cada recarga de la pagina renueva la sesion: con el cupo estrecho,
        //  diez recargas en un minuto dejaban a alguien afuera.
        for (int i = 0; i < 3; i++) {
            assertNull(pedir("POST", "/api/auth/refresh", "203.0.113.7"));
        }
        assertNull(pedir("POST", "/api/auth/acceso", "203.0.113.7"));
    }

    @Test
    void las_consultas_previas_del_navegador_no_cuentan() {
        for (int i = 0; i < 10; i++) {
            assertNull(pedir("OPTIONS", "/api/auth/acceso", "203.0.113.7"));
        }
    }

    @Test
    void la_cache_tiene_limite_y_elimina_ips_inactivas() {
        @SuppressWarnings("unchecked")
        Cache<String, Bucket> cache = (Cache<String, Bucket>) ReflectionTestUtils.getField(limitador, "buckets");
        assertEquals(50_000, cache.policy().eviction().orElseThrow().getMaximum());
        assertEquals(java.time.Duration.ofMinutes(10),
                cache.policy().expireAfterAccess().orElseThrow().getExpiresAfter());
    }
}
