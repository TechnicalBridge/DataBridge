package com.tbridge.auth.dto.response;

import com.tbridge.auth.model.AccessLog;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.hateoas.server.core.Relation;

import java.time.Instant;

/**
 * Una linea de la bitacora de acceso, para el panel (#141): cuando, por donde y
 * como termino. Sin nada que identifique a la persona: el RUT va a medias, y
 * ni el correo ni la IP salen de ms-auth (de la IP solo se guarda su huella).
 */
@Relation(collectionRelation = "accesos", itemRelation = "acceso")
@Schema(description = "Un intento de entrar, o un pedido de codigo o enlace, sin datos que identifiquen a la persona")
public record AccesoResponse(
        Instant cuando,
        @Schema(description = "code: con el codigo; magic_link: con el enlace", example = "code",
                allowableValues = {"code", "magic_link"})
        String metodo,
        @Schema(description = "granted: entro. invalid: el codigo o el enlace no corresponde. expired: vencio. "
                + "exhausted: demasiados intentos con ese codigo. sent: se pidio y salio el correo. refused: se "
                + "pidio y no salio (el RUT no tiene correo registrado o el correo no tiene cuenta), o la cuenta "
                + "no tiene acceso", example = "invalid",
                allowableValues = {"granted", "invalid", "expired", "exhausted", "sent", "refused"})
        String resultado,
        @Schema(description = "El RUT del deudor a medias: alcanza para ver un patron y no identifica a la persona. "
                + "Vacio si fue alguien del personal de una empresa, o un enlace que no existe",
                example = "18.***.***-6", nullable = true)
        String rut
) {

    public static AccesoResponse from(AccessLog registro) {
        return new AccesoResponse(registro.getOccurredAt(), registro.getMethod().name(), registro.getOutcome().name(),
                rutAMedias(registro.getDebtorRut()));
    }

    /**
     * 18905214-6 queda 18.***.***-6: se ven los primeros digitos y el digito
     * verificador, que comparten muchas personas, y se esconde el resto.
     */
    static String rutAMedias(String rut) {
        if (rut == null || !rut.contains("-")) {
            return null;
        }
        String cuerpo = rut.substring(0, rut.indexOf('-'));
        String dv = rut.substring(rut.indexOf('-') + 1);
        String inicio = cuerpo.length() > 6 ? cuerpo.substring(0, cuerpo.length() - 6) + "." : "";
        return inicio + "***.***-" + dv;
    }
}
