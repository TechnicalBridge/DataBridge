package com.tbridge.debt.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Los mandatos de la empresa de la sesion (#234). Una empresa que cobra lo
 * suyo, sin agencia, no tiene: el portal no le muestra nada de mandatos.
 */
@Schema(description = "Los mandatos de la empresa, si es una agencia de cobranza")
public record MandatosResponse(
        @Schema(description = "Si la empresa es una agencia: solo una agencia declara mandatos", example = "true")
        boolean agencia,
        List<MandatoPortalResponse> mandatos
) {
}
