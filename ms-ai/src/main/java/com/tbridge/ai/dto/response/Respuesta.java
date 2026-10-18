package com.tbridge.ai.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** Lo que responde el asistente. */
public record Respuesta(
        @Schema(description = "La respuesta para el deudor")
        String reply,
        @Schema(description = "El proveedor del LLM que respondio (ollama, groq, gemini) o local-nlp si las reglas",
                example = "local-nlp")
        String source,
        @Schema(description = "Cuantas deudas vio el asistente", example = "2")
        int debts,
        Animo sentimiento) {
}
