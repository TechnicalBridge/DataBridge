package com.tbridge.debt.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** Lo que hizo una pasada de las campanas. */
@Schema(description = "Cuantos toques de campana salieron y cuantos quedaron para despues")
public record ContactosResponse(
        @Schema(example = "3") int enviados,
        @Schema(description = "Tocaban, pero la ley no deja escribirle hoy al deudor, no tiene correo, su correo "
                + "tiene problemas, el correo no salio o ms-auth no respondio. Salen en una pasada siguiente, salvo "
                + "los de un correo con problemas", example = "1")
        int omitidos,
        @Schema(description = "Si era horario de cobranza: de lunes a sabado, de 8:00 a 20:00, sin feriados. "
                + "Fuera de el no sale nada", example = "true")
        boolean enHorario,
        @Schema(description = "Invitaciones que salieron en esta pasada: las de deudas que todavia no recibian "
                + "ningun correo, porque fallo, porque la ley lo dejo para despues o porque el deudor no tenia correo",
                example = "0")
        int invitados
) {
}
