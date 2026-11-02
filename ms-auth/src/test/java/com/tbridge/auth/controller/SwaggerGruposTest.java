package com.tbridge.auth.controller;

import com.tbridge.auth.assembler.SesionModelAssembler;
import com.tbridge.auth.config.OpenApiConfig;
import com.tbridge.auth.config.SecurityConfig;
import com.tbridge.auth.security.JwtService;
import com.tbridge.auth.service.AuthService;
import com.tbridge.auth.service.MailService;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.MultipleOpenApiSupportConfiguration;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Lo que Swagger ofrece en cada grupo (#198): lo publico se llama por la misma
 * direccion desde la que se abre Swagger, cada grupo ofrece en Authorize solo
 * la llave que usa, y lo interno dice que con Docker no se alcanza desde el
 * navegador y como llamarlo.
 */
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@WebMvcTest({InternalController.class, AuthController.class})
@Import({SecurityConfig.class, com.tbridge.auth.security.JwtTestConfig.class, SesionModelAssembler.class, OpenApiConfig.class})
@ImportAutoConfiguration({SpringDocConfiguration.class, SpringDocConfigProperties.class,
        SpringDocWebMvcConfiguration.class, MultipleOpenApiSupportConfiguration.class})
@ActiveProfiles("test")
class SwaggerGruposTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private AuthService auth;
    @MockitoBean private com.tbridge.auth.service.IdentifierLimit identifierLimit;
    @MockitoBean private MailService correo;

    @Test
    void el_portal_se_llama_por_el_gateway_y_ofrece_solo_el_jwt() throws Exception {
        mvc.perform(get("/v3/api-docs/portal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servers[0].url").value("/"))
                .andExpect(jsonPath("$.servers[0].description").value("El portal, por el gateway"))
                .andExpect(jsonPath("$.components.securitySchemes", aMapWithSize(1)))
                .andExpect(jsonPath("$.components.securitySchemes.jwt").exists());
    }

    @Test
    void lo_interno_dice_que_con_docker_no_se_alcanza_y_ofrece_solo_la_clave_interna() throws Exception {
        mvc.perform(get("/v3/api-docs/interno"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servers[0].url").value("http://localhost:8081"))
                .andExpect(jsonPath("$.servers[0].description").value(containsString("con Docker no se alcanza")))
                .andExpect(jsonPath("$.info.description")
                        .value(containsString("docker compose --profile app exec -T ms-auth sh -c")))
                .andExpect(jsonPath("$.components.securitySchemes", aMapWithSize(1)))
                .andExpect(jsonPath("$.components.securitySchemes['clave-interna']").exists());
        //  Cada grupo es su propia copia: quitarle el JWT a lo interno no se lo quita al portal.
        mvc.perform(get("/v3/api-docs/portal"))
                .andExpect(jsonPath("$.components.securitySchemes.jwt").exists());
    }
}
