package com.tbridge.auth.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CorregirPersonaRequest(
        @NotBlank @Size(max = 160) String nombre,
        @NotBlank @Size(max = 254) String correo) {
}
