package com.tbridge.payments.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/** Un pago con su historia, para su detalle en el panel (#435). */
@Schema(description = "Un pago con su historia")
public record PagoAdminDetalleResponse(
        PagoAdminResponse pago,
        @Schema(description = "Lo que le paso al pago, en orden") List<PaymentEventResponse> historia
) {
}
