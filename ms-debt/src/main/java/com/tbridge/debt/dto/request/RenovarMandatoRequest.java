package com.tbridge.debt.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

/** La fecha de término nueva de un mandato (#234). */
@Schema(description = "Hasta cuándo sigue el mandato")
public record RenovarMandatoRequest(
        @Schema(description = "De hoy en adelante. Vacío: sin fecha de término", example = "2027-09-30",
                nullable = true)
        String hasta
) {
}
