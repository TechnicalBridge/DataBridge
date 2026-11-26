package com.tbridge.debt.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CorregirEmpresaRequest(
        @NotBlank @Size(max = 160) String razonSocial,
        @NotBlank @Size(max = 120) String nombre) {
}
