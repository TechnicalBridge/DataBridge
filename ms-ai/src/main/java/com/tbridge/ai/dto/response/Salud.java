package com.tbridge.ai.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** Si el asistente esta arriba, si tiene un LLM y como va su cascada. */
public record Salud(
        boolean ok,
        String service,
        @Schema(description = "Si hay algun LLM configurado")
        boolean llm,
        @Schema(description = "La cascada de LLMs: quien hay y quien esta en enfriamiento")
        List<EstadoProveedor> proveedores) {

    /** Un proveedor de la cascada, y si ahora mismo se le esta saltando. */
    public record EstadoProveedor(
            @Schema(description = "ollama, groq o gemini", example = "groq")
            String nombre,
            @Schema(description = "Si tiene llave, o no la necesita como Ollama")
            boolean configurado,
            @Schema(description = "Si se le salta por un fallo o cuota reciente")
            boolean enEnfriamiento,
            @Schema(description = "Segundos que le quedan de enfriamiento", example = "0")
            long segundosRestantes) {
    }
}
