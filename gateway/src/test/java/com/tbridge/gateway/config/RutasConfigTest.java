package com.tbridge.gateway.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.context.ActiveProfiles;
import reactor.core.publisher.Mono;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A que servicio va cada ruta, y que lo que no tiene ruta no se alcanza desde afuera. */
@SpringBootTest
@ActiveProfiles("test")
class RutasConfigTest {

    @Autowired
    private RouteLocator rutas;

    /** El servicio al que llega esa ruta, o vacio si el gateway no la deja pasar. */
    private Optional<String> destino(String ruta) {
        MockServerWebExchange pedido = MockServerWebExchange.from(MockServerHttpRequest.get(ruta));
        return rutas.getRoutes()
                .filterWhen(r -> Mono.from(r.getPredicate().apply(pedido)))
                .next()
                .map(Route::getUri)
                .map(uri -> uri.getHost())
                .blockOptional();
    }

    @Test
    void cada_tramo_del_panel_de_administracion_llega_a_su_servicio() {
        assertEquals(Optional.of("ms-auth.invalid"), destino("/api/admin/auth/estado"));
        assertEquals(Optional.of("ms-debt.invalid"), destino("/api/admin/debt/estado"));
        assertEquals(Optional.of("ms-payments.invalid"), destino("/api/admin/payments/estado"));
    }

    @Test
    void un_tramo_del_panel_que_no_es_de_ningun_servicio_no_pasa() {
        assertEquals(Optional.empty(), destino("/api/admin/estado"));
        assertEquals(Optional.empty(), destino("/api/admin/otro/estado"));
    }

    @Test
    void los_portales_siguen_llegando_a_donde_llegaban() {
        assertEquals(Optional.of("ms-auth.invalid"), destino("/api/auth/enlace"));
        assertEquals(Optional.of("ms-auth.invalid"), destino("/api/me"));
        assertEquals(Optional.of("ms-debt.invalid"), destino("/api/debts/3"));
        assertEquals(Optional.of("ms-debt.invalid"), destino("/api/v1/carteras"));
        assertEquals(Optional.of("ms-payments.invalid"), destino("/api/payments/checkout"));
        assertEquals(Optional.of("ms-ai.invalid"), destino("/api/ai/chat"));
    }

    @Test
    void el_contrato_de_acceso_por_codigo_y_su_respaldo_llegan_a_auth() {
        for (String operacion : new String[]{"codigo", "acceso", "enlace", "verify", "refresh", "logout"}) {
            assertEquals(Optional.of("ms-auth.invalid"), destino("/api/auth/" + operacion));
        }
        // El alta y la emision interna no se publican para resolver un login.
        assertEquals(Optional.empty(), destino("/internal/codigos"));
    }

    @Test
    void lo_interno_no_tiene_puerta() {
        assertEquals(Optional.empty(), destino("/internal/codigos"));
        assertEquals(Optional.empty(), destino("/internal/uf"));
        assertEquals(Optional.empty(), destino("/internal/claves"));
    }
}
