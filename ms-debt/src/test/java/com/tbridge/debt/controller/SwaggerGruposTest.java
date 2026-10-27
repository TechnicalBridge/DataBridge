package com.tbridge.debt.controller;

import com.tbridge.debt.config.OpenApiConfig;
import com.tbridge.debt.config.SecurityConfig;
import com.tbridge.debt.security.JwtTestIssuer;
import com.tbridge.debt.service.ApiKeyService;
import com.tbridge.debt.service.CampanaAvanceService;
import com.tbridge.debt.service.ContactoService;
import com.tbridge.debt.service.DebtService;
import com.tbridge.debt.service.EmpresaService;
import com.tbridge.debt.service.MisDatosService;
import com.tbridge.debt.service.RecordatorioService;
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
@WebMvcTest({InternalController.class, MisDatosController.class})
@Import({SecurityConfig.class, com.tbridge.debt.security.JwtTestConfig.class, OpenApiConfig.class})
@ImportAutoConfiguration({SpringDocConfiguration.class, SpringDocConfigProperties.class,
        SpringDocWebMvcConfiguration.class, MultipleOpenApiSupportConfiguration.class})
@ActiveProfiles("test")
class SwaggerGruposTest {

    @Autowired private MockMvc mvc;
    @MockitoBean private DebtService debts;
    @MockitoBean private CampanaAvanceService avances;
    @MockitoBean private ApiKeyService claves;
    @MockitoBean private RecordatorioService recordatorios;
    @MockitoBean private ContactoService contactos;
    @MockitoBean private EmpresaService empresas;
    @MockitoBean private MisDatosService datos;

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
                .andExpect(jsonPath("$.servers[0].url").value("http://localhost:8083"))
                .andExpect(jsonPath("$.servers[0].description").value(containsString("con Docker no se alcanza")))
                .andExpect(jsonPath("$.info.description")
                        .value(containsString("docker compose --profile app exec -T ms-debt sh -c")))
                .andExpect(jsonPath("$.components.securitySchemes", aMapWithSize(1)))
                .andExpect(jsonPath("$.components.securitySchemes['clave-interna']").exists());
        //  Cada grupo es su propia copia: quitarle el JWT a lo interno no se lo quita al portal.
        mvc.perform(get("/v3/api-docs/portal"))
                .andExpect(jsonPath("$.components.securitySchemes.jwt").exists());
    }
}
