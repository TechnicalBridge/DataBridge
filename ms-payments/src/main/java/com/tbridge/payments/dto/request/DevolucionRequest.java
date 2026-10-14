package com.tbridge.payments.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;

public record DevolucionRequest(
        @Schema(description = "La referencia de devolucion entregada por la pasarela", example = "REF-DEV-99214")
        String referencia
) {}
