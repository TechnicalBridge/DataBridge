package com.tbridge.debt.controller;

import com.tbridge.debt.config.OpenApiConfig;
import com.tbridge.debt.config.SecurityConfig;
import com.tbridge.debt.exception.CarteraInvalidaHandler;
import com.tbridge.debt.security.JwtService;
import com.tbridge.debt.service.ApiKeyService;
import org.junit.jupiter.api.Test;
import org.springdoc.core.configuration.SpringDocConfiguration;
import org.springdoc.core.properties.SpringDocConfigProperties;
import org.springdoc.webmvc.core.configuration.MultipleOpenApiSupportConfiguration;
import org.springdoc.webmvc.core.configuration.SpringDocWebMvcConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.anEmptyMap;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Sin {@code INTEGRACION_API}, la entrega por API y las claves del portal no
 * existen: los deudores entran por campana. Responden 404 como cualquier ruta
 * que no hay, sin decir que estan apagadas, y ni siquiera se mira la clave.
 */
@WebMvcTest(controllers = {IntegracionController.class, ClaveController.class})
@Import({SecurityConfig.class, JwtService.class, CarteraInvalidaHandler.class, OpenApiConfig.class})
@ImportAutoConfiguration({SpringDocConfiguration.class, SpringDocConfigProperties.class,
        SpringDocWebMvcConfiguration.class, MultipleOpenApiSupportConfiguration.class})
@ActiveProfiles("test")
class EntregaPorApiApagadaTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;
    @MockitoBean private ApiKeyService claves;

    private String empresa() {
        return "Bearer " + jwt.issue("1", "camila.reyes@apofyx.cl", "CREDITOR", "Camila Reyes", "77305118-6");
    }

    @Test
    void la_entrega_por_api_responde_404_como_una_ruta_que_no_existe() throws Exception {
        mvc.perform(post("/api/v1/carteras").header("Authorization", "Bearer tbk_prueba")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"version\":\"1.0\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("No existe esa ruta"));
        mvc.perform(get("/api/v1/cuenta").header("Authorization", "Bearer tbk_prueba"))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/v1/campanas").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
        verify(claves, never()).autenticar(any());
    }

    @Test
    void la_empresa_no_tiene_claves_que_ver_ni_emitir() throws Exception {
        mvc.perform(get("/api/claves").header("Authorization", empresa()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("No existe esa ruta"));
        mvc.perform(post("/api/claves").header("Authorization", empresa())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nombre\":\"ERP\"}"))
                .andExpect(status().isNotFound());
        mvc.perform(delete("/api/claves/4").header("Authorization", empresa()))
                .andExpect(status().isNotFound());
        verify(claves, never()).emitir(any(), any());
    }

    @Test
    void swagger_muestra_el_contrato_vacio_y_dice_por_que() throws Exception {
        mvc.perform(get("/v3/api-docs/integracion"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("ms-debt · Contrato v1 (apagado)"))
                .andExpect(jsonPath("$.info.description").value(containsString("INTEGRACION_API=true")))
                .andExpect(jsonPath("$.paths").value(anEmptyMap()));
        //  El resto de la documentacion no cambia.
        mvc.perform(get("/v3/api-docs/portal"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("ms-debt · Deudas"));
    }
}
