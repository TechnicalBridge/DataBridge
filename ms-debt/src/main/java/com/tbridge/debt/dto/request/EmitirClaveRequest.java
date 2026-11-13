package com.tbridge.debt.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

/** Pedir una clave de API para una organizacion registrada. */
@Schema(description = "Para quien es la clave y un nombre para reconocerla")
public record EmitirClaveRequest(
        @Schema(example = "77305118-6")
        @NotBlank(message = "Falta el RUT de la organización")
        String rut,

        @Schema(example = "Reenvio de cartera APOFYX", nullable = true)
        String nombre
) {

    public String nombreOPorOmision() {
        return nombre == null || nombre.isBlank() ? "sin nombre" : nombre;
    }
}
