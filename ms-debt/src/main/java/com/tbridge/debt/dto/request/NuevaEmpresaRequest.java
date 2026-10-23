package com.tbridge.debt.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Dar de alta una empresa usuaria desde el panel de administracion. */
@Schema(description = "Una empresa usuaria: una agencia de cobranza, o un acreedor que cobra directo")
public record NuevaEmpresaRequest(
        @Schema(description = "Con o sin puntos y guion. Se revisa el digito verificador", example = "76543210-3")
        @NotBlank(message = "Falta el RUT de la empresa")
        @Size(max = 20, message = "El RUT es demasiado largo")
        String rut,

        @Schema(example = "Empresa Test SpA")
        @NotBlank(message = "Falta la razon social")
        @Size(max = 160, message = "La razon social puede tener hasta 160 caracteres")
        String razonSocial,

        @Schema(description = "El nombre con que se la conoce. Sin el, se usa la razon social",
                example = "Empresa Test", nullable = true)
        @Size(max = 120, message = "El nombre puede tener hasta 120 caracteres")
        String nombre,

        @Schema(description = "agencia: cobra por cuenta de otros. acreedor: cobra lo suyo, sin agencia",
                example = "acreedor", allowableValues = {"agencia", "acreedor"})
        @NotBlank(message = "Falta decir si es una agencia o un acreedor")
        @Pattern(regexp = "agencia|acreedor", message = "El tipo es agencia o acreedor")
        String tipo
) {
}
