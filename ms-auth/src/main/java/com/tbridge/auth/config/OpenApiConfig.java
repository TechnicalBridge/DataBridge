package com.tbridge.auth.config;

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
 * La documentacion de ms-auth en Swagger.
 *
 * <p>Tres grupos, porque tienen publico distinto: el <b>portal</b>, lo que llama
 * el navegador a traves del gateway; el <b>panel</b> de administracion, que es
 * solo del admin de DataBridge; y lo <b>interno</b>, lo que llaman los otros
 * servicios con la clave interna. El gateway no expone /internal, asi que
 * ese grupo apunta directo al servicio.
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
                        .title("ms-auth · Acceso al portal")
                        .version(version)
                        .description("""
                                Como entra cada quien a DataBridge.

                                - **El deudor** entra con su RUT y un codigo de acceso de un solo uso, \
                                sin cuenta ni contrasena. El codigo le llega por correo o WhatsApp y el \
                                entra escribiendo la direccion del portal: ningun mensaje trae un enlace \
                                en el que tenga que confiar.
                                - **El usuario de una empresa** entra con un enlace de un solo uso a su correo.
                                - **El admin de DataBridge** entra igual, con su enlace, y su sesion lleva el \
                                rol `ADMIN`: es el unico que entra al panel de administracion (`/api/admin`).

                                La sesion son dos piezas: un JWT de 15 minutos que va en el cuerpo, y una \
                                llave de renovacion revocable en la cookie `tb_renovacion` (HttpOnly, \
                                SameSite=Strict), que solo sirve en `/api/auth/refresh` y `/api/auth/logout`.""")
                        .contact(new Contact().name("Technical Bridge")))
                //  Relativo: Try it out llama a la misma direccion desde la que
                //  se abrio Swagger, sea localhost:8080 o el dominio.
                .servers(List.of(new Server().url("/").description("El portal, por el gateway")))
                .components(new Components()
                        .addSecuritySchemes(JWT, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP).scheme("bearer").bearerFormat("JWT")
                                .description("El `token` que devuelve `/api/auth/acceso` o `/api/auth/verify`."))
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
    public GroupedOpenApi interno(@Value("${app.openapi.url-interna:http://localhost:8081}") String urlInterna) {
        return GroupedOpenApi.builder()
                .group("interno")
                .displayName("Interno")
                .pathsToMatch("/internal/**")
                .addOpenApiCustomizer(directoAlServicio("ms-auth", urlInterna))
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

