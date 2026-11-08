package com.tbridge.debt.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/** Solicitud manual: QUEUED/enviado=false es persistencia, sin codigo ni promesa de entrega. */
@Schema(description = "A donde se mando el codigo, sin el codigo")
public record CodigoEnviadoResponse(
        @Schema(example = "true") boolean enviado,
        @Schema(example = "correo") String canal,
        @Schema(description = "Enmascarado: se reconoce sin exponerlo", example = "fe**********@correo.cl") String destino,
        @Schema(nullable = true) Instant expiraEn,
        @Schema(description = "QUEUED: solicitud persistida; ACCEPTED: aceptada por proveedor") String estado
) {
    public CodigoEnviadoResponse(boolean enviado, String canal, String destino, Instant expiraEn) {
        this(enviado, canal, destino, expiraEn, enviado ? "ACCEPTED" : "FAILED");
    }
}
