package com.tbridge.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Lo que queda al cerrar una sesion desde el panel (#439). La renovacion se
 * corta al tiro; el JWT que la persona ya tiene sirve hasta que vence, porque
 * no se revoca (#428).
 */
@Schema(description = "La sesion cerrada y hasta cuando puede durar el acceso que la persona ya tiene")
public record SesionCerradaResponse(
        String familia,
        @Schema(example = "camila.reyes@example.com")
        String correo,
        @Schema(description = "A mas tardar a esta hora termina su acceso: cuando vence el JWT que ya tiene")
        Instant accesoHasta
) {
}
