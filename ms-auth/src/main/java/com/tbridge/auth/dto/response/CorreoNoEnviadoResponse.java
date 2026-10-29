package com.tbridge.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Lo que se responde cuando el correo de un codigo no salio. Tiene la forma de
 * cualquier error ({@code error}), mas de quien es la culpa.
 */
@Schema(description = "El correo no salio: el codigo emitido se anulo y nadie lo recibio")
public record CorreoNoEnviadoResponse(
        @Schema(description = "Lo que respondio el servidor de correo. Nunca trae la clave",
                example = "550 5.1.1 Recipient address rejected: User unknown")
        String error,
        @Schema(description = "`direccion` si el servidor rechazo esa direccion, o esta mal escrita: insistir no "
                + "sirve. `servidor` si fue el servidor de correo (credenciales, cupo, no responde): se puede "
                + "reintentar mas tarde", allowableValues = {"direccion", "servidor"}, example = "direccion")
        String causa
) {

    public static final String DIRECCION = "direccion";
    public static final String SERVIDOR = "servidor";
}
