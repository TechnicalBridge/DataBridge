package com.tbridge.payments.dto.response;

import com.tbridge.payments.model.Payment;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.hateoas.server.core.Relation;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

/**
 * Un pago para la pantalla Pagos del panel (#435). Sin datos del deudor ni
 * ids o tokens de la pasarela: el deudor se ve solo en su ficha (#434).
 */
@Relation(collectionRelation = "pagos", itemRelation = "pago")
@Schema(description = "Un pago, para el admin de DataBridge")
public record PagoAdminResponse(
        @Schema(example = "2494") Long id,
        @Schema(description = "La deuda que paga, en ms-debt", example = "15") Long deuda,
        @Schema(description = "La empresa acreedora", example = "76418902-7") String acreedorRut,
        @Schema(example = "webpay") Payment.Gateway pasarela,
        @Schema(example = "paid") Payment.Status estado,
        @Schema(example = "125000") BigDecimal monto,
        @Schema(example = "CLP") Payment.Currency moneda,
        @Schema(description = "Lo que se cobro en pesos", nullable = true, example = "125000") Long montoEnPesos,
        @Schema(description = "Cuantas cuotas cubre", example = "1") int cuotas,
        Instant creado,
        @Schema(nullable = true) Instant pagado,
        @Schema(description = "Sigue abierto hace mas de una hora: hay que mirarlo") boolean abiertoHaceMasDeUnaHora,
        @Schema(description = "Se cobro dos veces: la empresa registra la devolucion") boolean duplicado
) {

    /** Cuanto tiene que llevar abierto un pago para resaltarlo. */
    public static final Duration ABIERTO_DE_MAS = Duration.ofHours(1);

    public static PagoAdminResponse de(Payment pago, Instant ahora) {
        boolean abiertoDeMas = pago.getStatus() == Payment.Status.created && pago.getCreatedAt() != null
                && pago.getCreatedAt().isBefore(ahora.minus(ABIERTO_DE_MAS));
        return new PagoAdminResponse(pago.getId(), pago.getDebtId(), pago.getCreditorRut(), pago.getGateway(),
                pago.getStatus(), pago.getAmount(), pago.getCurrency(), pago.getAmountClp(), pago.cuotas().size(),
                pago.getCreatedAt(), pago.getPaidAt(), abiertoDeMas, pago.getStatus() == Payment.Status.duplicated);
    }
}
