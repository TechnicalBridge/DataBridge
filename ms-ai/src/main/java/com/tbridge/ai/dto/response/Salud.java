package com.tbridge.ai.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** Si el asistente esta arriba y si tiene un LLM. */
public record Salud(
        boolean ok,
        String service,
        @Schema(description = "Si hay un LLM configurado")
        boolean llm) {
}
