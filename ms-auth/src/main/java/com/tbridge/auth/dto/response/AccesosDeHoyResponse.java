package com.tbridge.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;
import java.util.Map;

/** Los totales del dia de la bitacora de acceso, para la cabecera de *Accesos* en el panel (#141). */
@Schema(description = "Lo que paso hoy (dia de Chile) con el acceso")
public record AccesosDeHoyResponse(
        @Schema(description = "El dia, en Chile", example = "2026-10-27") LocalDate dia,
        @Schema(description = "Cuantas veces se entro", example = "12") long entraron,
        @Schema(description = "Intentos rechazados: codigo o enlace que no corresponde, vencido, demasiados "
                + "intentos, o un pedido que no salio", example = "3")
        long rechazados,
        @Schema(description = "Codigos y enlaces pedidos que salieron", example = "9") long enviados,
        @Schema(description = "Cuantos de cada resultado (granted, invalid, expired, exhausted, sent, refused)")
        Map<String, Long> porResultado
) {
}
