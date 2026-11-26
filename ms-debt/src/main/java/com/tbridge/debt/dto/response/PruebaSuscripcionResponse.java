package com.tbridge.debt.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** Lo que respondio el receptor al evento de prueba (#438). */
@Schema(description = "Lo que respondio el receptor al evento de prueba")
public record PruebaSuscripcionResponse(
        @Schema(example = "1") Long id,
        @Schema(description = "Si respondio 2xx") boolean respondio,
        @Schema(description = "El estado HTTP. Vacio si no hubo respuesta", nullable = true, example = "200")
        Integer estado,
        @Schema(description = "Lo que tardo, en milisegundos", example = "84") long milisegundos,
        @Schema(description = "Los primeros 200 caracteres de la respuesta, o por que no respondio", nullable = true)
        String detalle
) {
}
