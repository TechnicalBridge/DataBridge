package com.tbridge.payments.dto.response;

import com.tbridge.payments.model.Payment;
import io.swagger.v3.oas.annotations.media.Schema;

/** Lo que dijo la pasarela al verificar un pago abierto desde el panel (#435). */
@Schema(description = "El pago antes y despues de preguntarle a su pasarela")
public record PagoVerificadoResponse(
        @Schema(example = "2494") Long id,
        @Schema(example = "webpay") Payment.Gateway pasarela,
        @Schema(description = "Siempre abierto: solo se verifica un pago abierto", example = "created")
        Payment.Status antes,
        @Schema(description = "Como quedo: sigue abierto, pagado, fallido o vencido", example = "paid")
        Payment.Status ahora
) {
}
