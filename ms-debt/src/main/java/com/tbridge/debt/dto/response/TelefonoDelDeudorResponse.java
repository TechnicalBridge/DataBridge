package com.tbridge.debt.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * El telefono registrado para un RUT deudor.
 */
@Schema(description = "El telefono registrado de un deudor")
public record TelefonoDelDeudorResponse(
        @Schema(example = "+56987654321") String telefono
) {
}
