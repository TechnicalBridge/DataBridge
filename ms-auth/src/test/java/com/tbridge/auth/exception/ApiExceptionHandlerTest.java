package com.tbridge.auth.exception;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Cada error sale con su codigo y con la forma {"error": "..."}.
 *
 * Antes todo lo que no fuera ApiException respondia 500: un JSON mal escrito
 * parecia una caida del servicio.
 */
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class ApiExceptionHandlerTest {

    @Test
    void fallo_inesperado_no_registra_mensajes_personales_ni_causas(
            org.springframework.boot.test.system.CapturedOutput salida) {
        var respuesta = new ApiExceptionHandler().inesperado(new IllegalStateException(
                "Fallo persona@example.org", new RuntimeException("destino@example.net")));
        org.junit.jupiter.api.Assertions.assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, respuesta.getStatusCode());
        org.junit.jupiter.api.Assertions.assertEquals("Error interno", respuesta.getBody().error());
        org.junit.jupiter.api.Assertions.assertFalse(salida.getAll().contains("persona@example.org"));
        org.junit.jupiter.api.Assertions.assertFalse(salida.getAll().contains("destino@example.net"));
    }

    record Pedido(@NotBlank(message = "Falta el RUT") String rut) {}


    @RestController
    static class Prueba {
        @PostMapping("/validar")
        String validar(@Valid @RequestBody Pedido pedido) { return "ok"; }

        @GetMapping("/negocio")
        String negocio() { throw new ApiException(HttpStatus.CONFLICT, "Ya esta pagada"); }

        @GetMapping("/parametro")
        String parametro(@RequestParam Long id) { return "ok"; }

        @GetMapping("/roto")
        String roto() { throw new IllegalStateException("detalle interno que no debe salir"); }
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new Prueba())
            .setControllerAdvice(new ApiExceptionHandler())
            .build();

    @Test
    void una_regla_de_negocio_sale_con_su_codigo_y_su_mensaje() throws Exception {
        mvc.perform(get("/negocio"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("Ya esta pagada"));
    }

    @Test
    void un_campo_invalido_es_400_con_el_mensaje_de_su_anotacion() throws Exception {
        mvc.perform(post("/validar").contentType(MediaType.APPLICATION_JSON).content("{\"rut\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Falta el RUT"));
    }

    @Test
    void un_json_mal_escrito_es_400_y_no_500() throws Exception {
        mvc.perform(post("/validar").contentType(MediaType.APPLICATION_JSON).content("{rut"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void en_un_servicio_estricto_el_campo_que_no_existe_se_nombra() throws Exception {
        //  Como ms-payments: fail-on-unknown-properties encendido.
        MockMvc estricto = MockMvcBuilders.standaloneSetup(new Prueba())
                .setControllerAdvice(new ApiExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(
                        Jackson2ObjectMapperBuilder.json().failOnUnknownProperties(true).build()))
                .build();
        estricto.perform(post("/validar").contentType(MediaType.APPLICATION_JSON).content("{\"rut\":\"1-9\",\"otro\":2}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("La peticion trae un campo que no existe: 'otro'"));
    }

    @Test
    void un_parametro_con_otro_formato_o_ausente_es_400() throws Exception {
        mvc.perform(get("/parametro").param("id", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("El parametro 'id' no tiene el formato esperado"));
        mvc.perform(get("/parametro"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Falta el parametro 'id'"));
    }

    @Test
    void un_metodo_que_no_corresponde_es_405() throws Exception {
        mvc.perform(post("/negocio")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void lo_inesperado_es_500_sin_revelar_el_detalle() throws Exception {
        mvc.perform(get("/roto"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Error interno"));
    }
}
