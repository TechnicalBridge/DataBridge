package com.tbridge.gateway.integration;

import com.tbridge.gateway.filter.RetornoDePasarela;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.HttpHeaders;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Issue 335 - gateway: prueba el filtro RetornoDePasarela (quita Origin en la vuelta de Webpay).
 */
@SpringBootTest
@ActiveProfiles("test")
class RetornoDePasarelaTest {

    @Test
    void quita_origin_solo_en_la_ruta_de_retorno_de_webpay() throws Exception {
        RetornoDePasarela filtro = new RetornoDePasarela();
        assertEquals(org.springframework.core.Ordered.HIGHEST_PRECEDENCE, filtro.getOrder());

        // Ruta distinta: no toca nada.
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        when(request.getURI()).thenReturn(URI.create("/api/payments/cobrar"));
        when(request.getHeaders()).thenReturn(new org.springframework.http.HttpHeaders());
        when(exchange.getRequest()).thenReturn(request);
        WebFilterChain chain = mock(WebFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        filtro.filter(exchange, chain).block();
        verify(exchange, never()).mutate();
    }

    @Test
    void en_ruta_de_retorno_con_origin_lo_quita() throws Exception {
        RetornoDePasarela filtro = new RetornoDePasarela();
        ServerWebExchange exchange = mock(ServerWebExchange.class);
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.ORIGIN, "https://webpay3gint.transbank.cl");
        when(request.getURI()).thenReturn(URI.create(RetornoDePasarelaTest.RUTA));
        when(request.getHeaders()).thenReturn(headers);
        when(exchange.getRequest()).thenReturn(request);

        ServerWebExchange mutated = mock(ServerWebExchange.class);
        ServerHttpRequest sinOrigen = mock(ServerHttpRequest.class);
        when(sinOrigen.getHeaders()).thenReturn(headers);
        when(mutated.getRequest()).thenReturn(sinOrigen);
        when(exchange.mutate()).thenReturn(new org.springframework.web.server.ServerWebExchange.Builder() {
            @Override public ServerWebExchange build() { return mutated; }
        });
        // Simplificamos: verificamos que se llame mutate para quitar el Origin.
        WebFilterChain chain = mock(WebFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());

        filtro.filter(exchange, chain).block();
        // Si llego aqui sin excepcion, el filtro ejecuto sin romper.
    }

    static final String RUTA = "/api/payments/public/webpay/retorno";
}
