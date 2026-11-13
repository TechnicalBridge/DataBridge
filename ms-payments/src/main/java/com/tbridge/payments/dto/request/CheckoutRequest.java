package com.tbridge.payments.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Que se quiere pagar y por donde.
 *
 * <p>No trae monto a proposito: el monto lo dice ms-debt. Si lo mandara el
 * navegador, bastaria editar la peticion para pagar un peso.
 *
 * <p><b>Un campo que no existe se rechaza</b> (en ms-payments, todo cuerpo
 * lo hace: {@code fail-on-unknown-properties}). Aca ignorarlo cambiaba lo que
 * se cobra: cuando las cuotas pasaron de {@code installmentId} a
 * {@code installmentIds}, el portal siguio mandando el nombre viejo, el
 * servidor no lo vio y, sin cuotas, cobro todo el saldo. Mejor un 400 que un
 * cobro que nadie pidio.
 */
@Schema(description = "La deuda (o sus cuotas) a pagar y la pasarela")
public record CheckoutRequest(
        @Schema(example = "3")
        @NotNull(message = "Indica qué deuda quieres pagar")
        @Positive(message = "Indica qué deuda quieres pagar")
        Long debtId,

        @Schema(description = "Las cuotas a pagar: las que vencen primero, en orden. Sin ellas se paga todo "
                + "el saldo", example = "[12, 13]", nullable = true)
        @Size(max = 24, message = "Se pueden pagar hasta 24 cuotas a la vez")
        List<@NotNull(message = "Esa cuota no existe") @Positive(message = "Esa cuota no existe") Long> installmentIds,

        @Schema(allowableValues = {"webpay", "mercadopago", "khipu"}, example = "webpay")
        @NotBlank(message = "Pasarela no soportada (Mercado Pago, Khipu o Webpay)")
        @Pattern(regexp = "(?i)webpay|mercadopago|khipu", message = "Pasarela no soportada (Mercado Pago, Khipu o Webpay)")
        String gateway
) {
}
