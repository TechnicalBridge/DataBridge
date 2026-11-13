package com.tbridge.auth.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Canjear el enlace del correo por una sesion. */
@Schema(description = "El token que venia en el enlace (?token=...)")
public record VerifyRequest(
        @Schema(example = "3f0c8d1e-6a2b-4c55-9e7a-1b2c3d4e5f60")
        @NotBlank(message = "Al enlace le falta el token")
        @Size(max = 100, message = "Enlace inválido o vencido")
        String token
) {
}
