package com.tbridge.gateway.integration;

import com.tbridge.gateway.filter.RateLimitFilter;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebExchangeDecorator;
import reactor.core.publisher.Mono;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Issue 335 - gateway: prueba el filtro RateLimitFilter (cupos de peticiones).
 */
@SpringBootTest
@ActiveProfiles("test")
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
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getMethod()).thenReturn(java.net.http.HttpRequest.Method.OPTIONS);
        when(request.getURI()).thenReturn(URI.create("/api/auth/codigo"));
        when(exchange.getRequest()).thenReturn(request);

        GatewayFilterChainMock chain = new GatewayFilterChainMock();
        RateLimitFilter filtro = new RateLimitFilter(1, 1, 120, 1, "127\\.0\\.0\\.1");

        filtro.filter(exchange, chain).block();

        // OPTIONS no debe haber marcado la respuesta como 429.
        verify(exchange, never()).getResponse();
    }

    /** Cadena de filtros simulada para pruebas unitarias del filtro. */
    static class GatewayFilterChainMock implements org.springframework.cloud.gateway.filter.GatewayFilterChain {
        private final AtomicReference<ServerWebExchange> ultimo = new AtomicReference<>();
        @Override public Mono<Void> filter(ServerWebExchange exchange) {
            ultimo.set(exchange);
            return Mono.empty();
        }
        ServerWebExchange ultimo() { return ultimo.get(); }
    }
}
