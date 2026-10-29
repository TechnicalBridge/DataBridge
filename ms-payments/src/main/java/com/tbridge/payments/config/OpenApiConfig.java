package com.tbridge.payments.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * La documentacion de ms-payments en Swagger: el grupo del <b>portal</b>
 * (a traves del gateway) y el <b>interno</b> (directo al servicio, con la
 * clave interna).
 *
 * <p>Cada grupo ofrece en Authorize solo las llaves que piden sus operaciones.
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
                //  Relativo: Try it out llama a la misma direccion desde la que
                //  se abrio Swagger, sea localhost:8080 o el dominio.
                .servers(List.of(new Server().url("/").description("El portal, por el gateway")))
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
                .addOpenApiCustomizer(soloSusLlaves())
                .build();
    }

    @Bean
    public GroupedOpenApi panel() {
        return GroupedOpenApi.builder()
                .group("panel")
                .displayName("Panel de administracion")
                .pathsToMatch("/api/admin/**")
                .addOpenApiCustomizer(soloSusLlaves())
                .build();
    }

    @Bean
    public GroupedOpenApi interno(@Value("${app.openapi.url-interna:http://localhost:8084}") String urlInterna) {
        return GroupedOpenApi.builder()
                .group("interno")
                .displayName("Interno")
                .pathsToMatch("/internal/**")
                .addOpenApiCustomizer(directoAlServicio("ms-payments", urlInterna))
                .addOpenApiCustomizer(soloSusLlaves())
                .build();
    }

    /**
     * Lo interno apunta directo al servicio, cuyo puerto Docker no publica, a
     * proposito: Try it out no llega desde el navegador. Swagger lo dice, y
     * como llamarlo desde dentro del contenedor, que ya tiene la clave.
     */
    static OpenApiCustomizer directoAlServicio(String servicio, String urlInterna) {
        return api -> api
                .servers(List.of(new Server()
                        .url(urlInterna)
                        .description("Directo al servicio: con Docker no se alcanza desde el navegador")))
                .info(new Info()
                        .title(api.getInfo().getTitle() + " · interno")
                        .version(api.getInfo().getVersion())
                        .description("""
                                Lo que se llaman los servicios entre ellos, con la clave interna                                 (`X-Internal-Key`). El gateway no expone `/internal`, asi que este grupo apunta                                 directo al servicio, en `%2$s`.

                                **Con Docker, Try it out no llega:** ese puerto no se publica, a proposito. Se                                 llama desde dentro del contenedor, que ya tiene la clave (con `-X POST` y el                                 cuerpo si la operacion lo pide):

                                    docker compose --profile app exec -T %1$s sh -c 'curl -s %2$s/internal/... -H X-Internal-Key:$INTERNAL_KEY'

                                Con el servicio corriendo fuera de Docker, Try it out funciona.""".formatted(servicio, urlInterna)));
    }

    /**
     * Deja en Authorize solo las llaves que piden las operaciones del grupo: el
     * portal no ofrece la clave interna, ni lo interno el JWT. Cada grupo es su
     * propia copia del documento, asi que no le quita nada a los otros.
     */
    static OpenApiCustomizer soloSusLlaves() {
        return api -> {
            Set<String> usadas = new HashSet<>();
            if (api.getPaths() != null) {
                api.getPaths().values().forEach(ruta -> ruta.readOperations().forEach(operacion -> {
                    if (operacion.getSecurity() != null) {
                        operacion.getSecurity().forEach(requisito -> usadas.addAll(requisito.keySet()));
                    }
                }));
            }
            if (api.getComponents() != null && api.getComponents().getSecuritySchemes() != null) {
                api.getComponents().getSecuritySchemes().keySet().retainAll(usadas);
            }
        };
    }
}

