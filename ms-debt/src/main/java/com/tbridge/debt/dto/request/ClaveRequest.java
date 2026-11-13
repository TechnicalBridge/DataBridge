package com.tbridge.debt.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Pedir una clave de API desde el portal, para la organizacion de la sesion. */
@Schema(description = "Un nombre para reconocer la clave: para que sistema es")
public record ClaveRequest(
        @Schema(example = "Servidor de APOFYX")
        @NotBlank(message = "Ponle un nombre a la clave, para saber después de qué sistema es")
        @Size(max = 80, message = "El nombre puede tener hasta 80 caracteres")
        String nombre
) {
}
