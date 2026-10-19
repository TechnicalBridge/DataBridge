package com.tbridge.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/** Pedir codigo o respaldo por correo, segun la ruta. */
@Schema(description = "RUT del deudor o correo del personal para solicitar acceso")
public record EnlaceRequest(
        @Schema(description = "Solo para deudores. El personal de una empresa lo deja vacio",
                example = "16482337-7", nullable = true)
        @Size(max = 20, message = "El RUT es demasiado largo")
        String rut,

        @Schema(description = "Solo para el personal. El deudor no lo manda: su acceso va al correo "
                + "que registro el acreedor", example = "camila.reyes@apofyx.cl", nullable = true)
        @Email(message = "Correo no valido")
        @Size(max = 254, message = "Correo no valido")
        String correo
) {
}
