package com.tbridge.ai.dto.request;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreguntaValidationTest {

    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void limita_el_mensaje_nuevo_y_la_cantidad_de_mensajes_anteriores() {
        assertFalse(validator.validate(new Pregunta("", null)).isEmpty());
        assertFalse(validator.validate(new Pregunta("x".repeat(1001), null)).isEmpty());
        assertFalse(validator.validate(new Pregunta("saldo", Collections.nCopies(13,
                Map.of("role", "user", "content", "hola")))).isEmpty());
        assertTrue(validator.validate(new Pregunta("saldo", List.of())).isEmpty());
    }

    @Test
    void rechaza_contenido_enorme_o_que_no_es_texto() {
        assertFalse(validator.validate(new Pregunta("saldo", List.of(
                Map.of("role", "user", "content", "x".repeat(1001))))).isEmpty());
        assertFalse(validator.validate(new Pregunta("saldo", List.of(
                Map.of("role", "user", "content", List.of("texto"))))).isEmpty());
        assertTrue(validator.validate(new Pregunta("saldo", List.of(
                Map.of("role", "assistant", "content", "hola")))).isEmpty());
    }
}
