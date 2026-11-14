package com.tbridge.payments.controller;

import com.tbridge.payments.exception.ApiError;
import com.tbridge.payments.exception.ApiException;
import com.tbridge.payments.model.Payment;
import com.tbridge.payments.dto.request.WebhookRequest;
import com.tbridge.payments.dto.response.PaymentResponse;
import com.tbridge.payments.service.PaymentService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/** Donde avisan las pasarelas. Sin sesion: lo protege la firma del aviso. */
@RestController
@Tag(name = "Pasarelas", description = "Los avisos firmados de Webpay, Mercado Pago y Khipu")
public class WebhookController {

    private final PaymentService payments;

    public WebhookController(PaymentService payments) {
        this.payments = payments;
    }

    @PostMapping("/api/payments/webhooks/{gateway}")
    @Operation(summary = "Aviso de pago de una pasarela",
            description = """
                    Confirma el pago si la firma HMAC calza. Un aviso con firma invalida no se aplica, pero \
                    queda en el libro del pago. El mismo aviso puede llegar varias veces: la segunda no cobra.""")
    @ApiResponse(responseCode = "200", description = "El pago, ya pagado")
    @ApiResponse(responseCode = "400", description = "El aviso no dice que pago es",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "401", description = "Firma invalida",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Esa transaccion de la pasarela ya estaba registrada",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public PaymentResponse webhook(
            @Parameter(description = "webpay, mercadopago o khipu", example = "webpay") @PathVariable String gateway,
            @Parameter(description = "HMAC del pago") @RequestHeader(value = "X-Signature", required = false) String firma,
            @RequestBody WebhookRequest aviso
    ) {
        Payment.Gateway pasarela = validarPasarela(gateway);
        return payments.webhook(pasarela, aviso, firma);
    }

    private Payment.Gateway validarPasarela(String gateway) {
        if (gateway == null || gateway.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Pasarela requerida");
        }
        try {
            return Payment.Gateway.valueOf(gateway.trim().toLowerCase());
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Pasarela no soportada: " + gateway);
        }
    }
}
