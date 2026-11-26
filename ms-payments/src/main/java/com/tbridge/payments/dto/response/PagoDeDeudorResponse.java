package com.tbridge.payments.dto.response;

import com.tbridge.payments.model.Payment;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;

/** Un pago de un deudor, para su ficha en el panel (#434). Sin ids ni tokens de la pasarela. */
@Schema(description = "Un pago de un deudor, para el admin de DataBridge")
public record PagoDeDeudorResponse(
        @Schema(example = "2494") Long id,
        @Schema(description = "La deuda que paga, en ms-debt", example = "15") Long deuda,
        @Schema(example = "76418902-7") String acreedorRut,
        @Schema(example = "webpay") Payment.Gateway pasarela,
        @Schema(example = "paid") Payment.Status estado,
        @Schema(example = "125000") BigDecimal monto,
        @Schema(example = "CLP") Payment.Currency moneda,
        @Schema(description = "Lo que se cobro en pesos", nullable = true, example = "125000") Long montoEnPesos,
        @Schema(description = "Cuantas cuotas cubre", example = "1") int cuotas,
        Instant creado,
        @Schema(nullable = true) Instant pagado
) {

    public static PagoDeDeudorResponse de(Payment pago) {
        return new PagoDeDeudorResponse(pago.getId(), pago.getDebtId(), pago.getCreditorRut(), pago.getGateway(),
                pago.getStatus(), pago.getAmount(), pago.getCurrency(), pago.getAmountClp(), pago.cuotas().size(),
                pago.getCreatedAt(), pago.getPaidAt());
    }
}
