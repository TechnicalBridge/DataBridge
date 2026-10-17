package com.tbridge.debt.config;

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
 * La documentacion de ms-debt en Swagger, en tres grupos segun quien llama:
 *
 * <ul>
 *   <li><b>portal</b>: el navegador del deudor o de la empresa, con el JWT.</li>
 *   <li><b>integracion</b>: el contrato v1, que usan los sistemas de las
 *       agencias y acreedores con su clave de API. Con la entrega por API
 *       apagada, que es lo por omision, queda sin operaciones y lo dice.</li>
 *   <li><b>interno</b>: los otros servicios, con la clave interna. El gateway
 *       no lo expone, asi que apunta directo al servicio.</li>
 * </ul>
 *
 * <p>Cada grupo ofrece en Authorize solo las llaves que piden sus operaciones.
 */
@Configuration
public class OpenApiConfig {

    public static final String JWT = "jwt";
    public static final String CLAVE_API = "clave-api";
    public static final String CLAVE_INTERNA = "clave-interna";

    @Bean
    public OpenAPI documentacion(@Value("${app.version:1.0.0}") String version) {
        return new OpenAPI()
                .info(new Info()
                        .title("ms-debt · Deudas")
                        .version(version)
                        .description("""
                                Las deudas de DataBridge: la cartera que entrega la agencia o el acreedor, lo que \
                                ve el deudor, los planes de cuotas y lo que se le avisa de vuelta a quien entrego.

                                - **Cada quien ve lo suyo**: el deudor, sus deudas; la empresa, la cartera que \
                                opera. Nunca "todas".
                                - **El saldo se calcula** desde las cuotas pendientes; no se guarda.
                                - **Pesos y UF no se suman**: cada monto viaja con su moneda.
                                - Los enlaces (`_links`) dicen que se puede hacer con cada deuda segun quien mira.""")
                        .contact(new Contact().name("Technical Bridge")))
                //  Relativo: Try it out llama a la misma direccion desde la que
                //  se abrio Swagger, sea localhost:8080 o el dominio.
                .servers(List.of(new Server().url("/").description("El portal, por el gateway")))
                .components(new Components()
                        .addSecuritySchemes(JWT, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                                .description("El `token` de la sesion del portal (ms-auth)."))
                        .addSecuritySchemes(CLAVE_API, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("tbk_...")
                                .description("""
                                        La clave de API del emisor, y se comprueba con `GET /api/v1/cuenta`. \
                                        Solo con `INTEGRACION_API=true`: por omision la entrega por API esta \
                                        apagada."""))
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
                .pathsToExclude("/api/v1/**", "/api/admin/**")
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
    public GroupedOpenApi integracion(@Value("${app.integracion.api:false}") boolean encendida) {
        GroupedOpenApi.Builder grupo = GroupedOpenApi.builder()
                .group("integracion")
                .displayName("Integracion (contrato v1)")
                .pathsToMatch("/api/v1/**")
                .addOpenApiCustomizer(soloSusLlaves());
        //  Apagada no hay controlador, y el grupo queda vacio. Sigue existiendo
        //  para que Swagger diga por que, en vez de un error al elegirlo.
        if (!encendida) {
            grupo.addOpenApiCustomizer(api -> api.info(new Info()
                    .title("ms-debt · Contrato v1 (apagado)")
                    .version(api.getInfo().getVersion())
                    .description("""
                            La entrega por API esta apagada: los deudores entran por campana, cargados desde \
                            el portal. Se enciende con `INTEGRACION_API=true`.""")));
        }
        return grupo.build();
    }

    @Bean
    public GroupedOpenApi interno(@Value("${app.openapi.url-interna:http://localhost:8083}") String urlInterna) {
        return GroupedOpenApi.builder()
                .group("interno")
                .displayName("Interno")
                .pathsToMatch("/internal/**")
                .addOpenApiCustomizer(directoAlServicio("ms-debt", urlInterna))
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
