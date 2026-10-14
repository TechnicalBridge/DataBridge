package com.tbridge.auth.config;

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
 * La documentacion de ms-auth en Swagger.
 *
 * <p>Tres grupos, porque tienen publico distinto: el <b>portal</b>, lo que llama
 * el navegador a traves del gateway; el <b>panel</b> de administracion, que es
 * solo del admin de DataBridge; y lo <b>interno</b>, lo que llaman los otros
 * servicios con la clave interna. El gateway no expone /internal, asi que
 * ese grupo apunta directo al servicio.
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
                                sin cuenta ni contrasena. El codigo le llega por correo y el \
                                entra escribiendo la direccion del portal: ningun mensaje trae un enlace \
                                en el que tenga que confiar.
                                - **El usuario de una empresa** entra con un enlace de un solo uso a su correo.
                                - **El admin de DataBridge** entra igual, con su enlace, y su sesion lleva el \
                                rol `ADMIN`: es el unico que entra al panel de administracion (`/api/admin`).

                                La sesion son dos piezas: un JWT de 15 minutos que va en el cuerpo, y una \
                                llave de renovacion revocable en la cookie `tb_renovacion` (HttpOnly, \
                                SameSite=Strict), que solo sirve en `/api/auth/refresh` y `/api/auth/logout`.""")
                        .contact(new Contact().name("Technical Bridge")))
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
    public GroupedOpenApi interno(@Value("${app.openapi.url-interna:http://localhost:8081}") String urlInterna) {
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
