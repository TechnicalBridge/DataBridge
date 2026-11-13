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
import org.springframework.web.multipart.MaxUploadSizeExceededException;

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
class ApiExceptionHandlerTest {

    record Pedido(@NotBlank(message = "Falta el RUT") String rut) {}


    @RestController
    static class Prueba {
        @PostMapping("/validar")
        String validar(@Valid @RequestBody Pedido pedido) { return "ok"; }

        @GetMapping("/negocio")
        String negocio() { throw new ApiException(HttpStatus.CONFLICT, "Ya esta pagada"); }

        @GetMapping("/parametro")
        String parametro(@RequestParam Long id) { return "ok"; }

        @PostMapping("/archivo")
        String archivo() { throw new MaxUploadSizeExceededException(1); }

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
                .andExpect(jsonPath("$.error").value("La petición trae un campo que no existe: 'otro'"));
    }

    @Test
    void un_parametro_con_otro_formato_o_ausente_es_400() throws Exception {
        mvc.perform(get("/parametro").param("id", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("El parámetro 'id' no tiene el formato esperado"));
        mvc.perform(get("/parametro"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Falta el parámetro 'id'"));
    }

    @Test
    void un_metodo_que_no_corresponde_es_405() throws Exception {
        mvc.perform(post("/negocio")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void un_archivo_demasiado_grande_es_413_con_su_mensaje() throws Exception {
        mvc.perform(post("/archivo"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.error").value("El archivo pasa del tamaño máximo permitido"));
    }

    @Test
    void lo_inesperado_es_500_sin_revelar_el_detalle() throws Exception {
        mvc.perform(get("/roto"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.error").value("Error interno"));
    }
}
