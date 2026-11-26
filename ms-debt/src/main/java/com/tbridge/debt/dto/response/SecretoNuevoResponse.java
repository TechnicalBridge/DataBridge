package com.tbridge.debt.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** El secreto nuevo de una suscripcion (#438). Se entrega esta unica vez: despues no se vuelve a mostrar. */
@Schema(description = "El secreto nuevo de una suscripcion, que se muestra una sola vez")
public record SecretoNuevoResponse(
        @Schema(example = "1") Long id,
        @Schema(description = "Con el se verifica X-Firma desde ahora. Guardarlo como secreto", example = "whsec_...")
        String secreto,
        @Schema(example = "X-Firma: v1=HMAC-SHA256(secreto, X-Timestamp + \".\" + cuerpo), en hex") String firma
) {
}
