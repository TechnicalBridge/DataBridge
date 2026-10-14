package com.tbridge.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Dar de alta a una persona desde el panel de administracion.
 *
 * <p>No trae rol: lo decide la ruta. Bajo una empresa la persona es
 * {@code usuario}; bajo {@code /admins}, {@code admin}. Un {@code rol} que
 * venga en el cuerpo no se lee, asi que no hay forma de pedir un admin para
 * una empresa.
 */
@Schema(description = "A quien se le da acceso: su correo y su nombre")
public record NuevaPersonaRequest(
        @Schema(description = "A donde le llega su enlace de acceso", example = "ana.soto@empresatest.cl")
        @NotBlank(message = "Falta el correo de la persona")
        @Size(max = 254, message = "El correo es demasiado largo")
        String correo,

        @Schema(example = "Ana Soto")
        @NotBlank(message = "Falta el nombre de la persona")
        @Size(max = 160, message = "El nombre puede tener hasta 160 caracteres")
        String nombre
) {
}
