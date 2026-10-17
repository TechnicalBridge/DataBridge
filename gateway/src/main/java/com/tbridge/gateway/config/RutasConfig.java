package com.tbridge.gateway.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * A que servicio va cada ruta.
 *
 * <p><b>Lo que no esta aqui no se puede alcanzar desde afuera.</b> En
 * particular {@code /internal}: lo usan los servicios entre ellos, con la
 * clave interna, y no tiene por que tener una puerta publica.
 *
 * <p>Las rutas {@code /v3/api-docs/...} traen la documentacion de cada
 * servicio para el Swagger unificado de este gateway: cada servicio publica
 * la suya en {@code /v3/api-docs/{grupo}}, y aqui se le antepone su nombre.
 */
@Configuration
public class RutasConfig {

    @Bean
    public RouteLocator rutas(RouteLocatorBuilder rutas,
                              @Value("${app.servicios.auth}") String auth,
                              @Value("${app.servicios.debt}") String debt,
                              @Value("${app.servicios.payments}") String payments,
                              @Value("${app.servicios.ai}") String ai) {
        return rutas.routes()
                .route("ms-auth", r -> r.path("/api/auth/**", "/api/me").uri(auth))
                //  /api/v1 es el contrato de integracion: los sistemas de las
                //  agencias entran por la misma puerta que el portal.
                .route("ms-debt", r -> r.path("/api/debts/**", "/api/analytics/**", "/api/claves/**", "/api/v1/**").uri(debt))
                .route("ms-payments", r -> r.path("/api/payments/**").uri(payments))
                .route("ms-ai", r -> r.path("/api/ai/**").uri(ai))

                //  El panel de administracion de DataBridge. Cada servicio atiende
                //  su parte y exige el mismo que la sesion sea de un admin.
                .route("panel-ms-auth", r -> r.path("/api/admin/auth/**").uri(auth))
                .route("panel-ms-debt", r -> r.path("/api/admin/debt/**").uri(debt))
                .route("panel-ms-payments", r -> r.path("/api/admin/payments/**").uri(payments))

                .route("docs-ms-auth", r -> r.path("/v3/api-docs/ms-auth/**")
                        .filters(f -> f.rewritePath("/v3/api-docs/ms-auth/(?<grupo>.*)", "/v3/api-docs/${grupo}"))
                        .uri(auth))
                .route("docs-ms-debt", r -> r.path("/v3/api-docs/ms-debt/**")
                        .filters(f -> f.rewritePath("/v3/api-docs/ms-debt/(?<grupo>.*)", "/v3/api-docs/${grupo}"))
                        .uri(debt))
                .route("docs-ms-payments", r -> r.path("/v3/api-docs/ms-payments/**")
                        .filters(f -> f.rewritePath("/v3/api-docs/ms-payments/(?<grupo>.*)", "/v3/api-docs/${grupo}"))
                        .uri(payments))
                .route("docs-ms-ai", r -> r.path("/v3/api-docs/ms-ai/**")
                        .filters(f -> f.rewritePath("/v3/api-docs/ms-ai/(?<grupo>.*)", "/v3/api-docs/${grupo}"))
                        .uri(ai))
                .build();
    }
}
