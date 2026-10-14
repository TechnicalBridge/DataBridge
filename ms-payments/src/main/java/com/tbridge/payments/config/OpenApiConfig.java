package com.tbridge.payments.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * La documentacion de ms-payments en Swagger: el grupo del <b>portal</b>
 * (a traves del gateway) y el <b>interno</b> (directo al servicio, con la
 * clave interna).
 */
@Configuration
public class OpenApiConfig {

    public static final String JWT = "jwt";
    public static final String CLAVE_INTERNA = "clave-interna";

    @Bean
    public OpenAPI documentacion(@Value("${app.version:1.0.0}") String version) {
        return new OpenAPI()
                .info(new Info()
                        .title("ms-payments · Pagos")
                        .version(version)
                        .description("""
                                El cobro de una deuda o de una cuota.

                                - **El monto no lo manda el navegador**: se lo pregunta a ms-debt. El cliente solo \
                                dice que deuda quiere pagar y por que pasarela.
                                - **Nada se sobreescribe**: cada paso queda en el libro del pago (`/historia`).
                                - **En UF**, los pesos se fijan al abrir el cobro con la UF de ese dia, y el valor \
                                usado queda guardado junto al pago.
                                - Confirmado el pago, el aviso a ms-debt sale despues, con reintentos, por HTTP o \
                                por RabbitMQ.""")
                        .contact(new Contact().name("Technical Bridge")))
                .components(new Components()
                        .addSecuritySchemes(JWT, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                                .description("El `token` de la sesion del portal (ms-auth)."))
                        .addSecuritySchemes(CLAVE_INTERNA, new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY).in(SecurityScheme.In.HEADER).name("X-Internal-Key")
                                .description("La clave que comparten los servicios (`INTERNAL_KEY`).")));
    }

    @Bean
    public GroupedOpenApi portal() {
        return GroupedOpenApi.builder()
                .group("portal")
                .displayName("Portal")
                .pathsToMatch("/api/**")
                .pathsToExclude("/api/admin/**")
                .build();
    }

    @Bean
    public GroupedOpenApi panel() {
        return GroupedOpenApi.builder()
                .group("panel")
                .displayName("Panel de administracion")
                .pathsToMatch("/api/admin/**")
                .build();
    }

    @Bean
    public GroupedOpenApi interno(@Value("${app.openapi.url-interna:http://localhost:8084}") String urlInterna) {
        return GroupedOpenApi.builder()
                .group("interno")
                .displayName("Interno")
                .pathsToMatch("/internal/**")
                .addOpenApiCustomizer(api -> api.servers(List.of(new Server()
                        .url(urlInterna)
                        .description("Directo al servicio: el gateway no expone /internal"))))
                .build();
    }
}
