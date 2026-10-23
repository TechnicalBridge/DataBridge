package com.tbridge.debt.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** Lo que responde un servicio cuando el panel de administracion le pregunta si esta arriba. */
@Schema(description = "El estado de un servicio, para el panel de administracion")
public record EstadoResponse(
        @Schema(example = "ms-debt") String servicio,
        @Schema(description = "Si responde", example = "true") boolean ok
) {
}
