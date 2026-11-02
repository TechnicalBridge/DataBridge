package com.tbridge.payments.dto.request;

import com.tbridge.payments.service.PaymentService;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Lo que la empresa anota al registrar que devolvio un pago duplicado. */
public record DevolucionRequest(
        @Schema(description = "La referencia de la devolucion que dio la pasarela", example = "DEV-99214",
                maxLength = PaymentService.MAX_REFERENCIA)
        @NotBlank(message = "Falta la referencia de la devolucion en la pasarela")
        @Size(max = PaymentService.MAX_REFERENCIA,
                message = "La referencia de la devolucion tiene mas de " + PaymentService.MAX_REFERENCIA + " caracteres")
        String referencia
) {}
