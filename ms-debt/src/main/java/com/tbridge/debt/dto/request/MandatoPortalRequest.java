package com.tbridge.debt.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * El mandato que la agencia declara en su portal (#234): lo mismo que el
 * contrato 2, para la agencia sin un sistema propio.
 */
@Schema(description = "Un mandato: la agencia cobra por cuenta de este acreedor")
public record MandatoPortalRequest(
        @Schema(description = "Con o sin puntos y guion. Se revisa el dígito verificador", example = "76418902-7")
        @NotBlank(message = "Falta el RUT del acreedor")
        @Size(max = 20, message = "El RUT es demasiado largo")
        String acreedorRut,

        @Schema(description = "Solo si DataBridge todavía no conoce al acreedor", example = "Patrimonio Inmuebles SpA",
                nullable = true)
        @Size(max = 160, message = "La razón social puede tener hasta 160 caracteres")
        String razonSocial,

        @Schema(description = "El nombre que ve el deudor. Sin él, la razón social", example = "Patrimonio Inmuebles",
                nullable = true)
        @Size(max = 120, message = "El nombre puede tener hasta 120 caracteres")
        String nombre,

        @Schema(description = "Sin indicar, hoy", example = "2026-10-01", nullable = true)
        String desde,

        @Schema(description = "Sin indicar, sin fecha de término", example = "2027-09-30", nullable = true)
        String hasta,

        @Schema(description = "Pasados estos días de mora el caso vuelve al acreedor. Sin indicar, 120",
                example = "120", nullable = true)
        Integer moraMaximaDias,

        @Schema(description = "El % de los intereses de mora que el acreedor autoriza condonar, de 0 a 100. "
                + "Sin indicar, 0: ninguna campaña puede ofrecer descuento", example = "50", nullable = true)
        BigDecimal descuentoMaximoMora
) {
}
