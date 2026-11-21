package com.tbridge.auth.dto.response;

import com.tbridge.auth.model.AccessLog;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Un pedido de codigo o un intento de entrar de un deudor, para su ficha en el
 * panel (#434). Sin el codigo, su huella ni la IP.
 */
@Schema(description = "Un pedido de codigo o un intento de entrar del deudor")
public record IngresoDeDeudorResponse(
        @Schema(description = "Con codigo o con enlace", example = "code") AccessLog.Method metodo,
        @Schema(description = "sent: se le envio; granted: entro; los demas, rechazado", example = "granted")
        AccessLog.Outcome resultado,
        Instant cuando
) {

    public static IngresoDeDeudorResponse de(AccessLog registro) {
        return new IngresoDeDeudorResponse(registro.getMethod(), registro.getOutcome(), registro.getOccurredAt());
    }
}
