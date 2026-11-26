package com.tbridge.debt.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;

/**
 * Como va la pasada de contacto, para el panel (#441): la ultima, si termino
 * bien o con error, cuando toca la proxima y si la ley esta activa. Sin datos
 * de ningun deudor.
 */
@Schema(description = "La pasada de contacto: la ultima, la proxima y si el horario y el limite de la ley estan activos")
public record ContactoEstadoResponse(
        @Schema(description = "Desde cuando cuenta: la ultima pasada se guarda en memoria y se pierde al reiniciar "
                + "ms-debt")
        Instant desde,
        @Schema(description = "La ultima pasada que termino; vacia si no ha pasado ninguna desde que arranco",
                nullable = true)
        Pasada ultima,
        @Schema(description = "Si hay una pasada corriendo, desde cuando", nullable = true)
        Instant enCursoDesde,
        @Schema(description = "Cuando toca la proxima, segun CONTACTO_CRON")
        Instant proxima,
        @Schema(description = "Si ya debio haber pasado otra y no paso: la tarea se detuvo")
        boolean atrasada,
        @Schema(description = "Si se respeta el horario de la ley: false con CONTACTO_SIN_HORARIO=true, solo para probar")
        boolean horarioLegal,
        @Schema(description = "Si se respeta el limite de dos correos por semana: false con CONTACTO_SIN_LIMITE=true, "
                + "solo para probar")
        boolean limiteSemanal
) {

    /** Una pasada que termino: lo que hizo, o con que error se cayo. */
    @Schema(description = "Una pasada de contacto que termino")
    public record Pasada(
            Instant inicio,
            Instant fin,
            @Schema(description = "Si era horario de cobranza: fuera de el no sale nada, y esta bien")
            boolean enHorario,
            @Schema(description = "Toques de campana que salieron") int enviados,
            @Schema(description = "Invitaciones pendientes que salieron") int invitados,
            @Schema(description = "Tocaban, pero la ley, el correo del deudor o el servidor de correo no dejaron: "
                    + "salen en una pasada siguiente") int pendientes,
            @Schema(description = "Si se cayo, el tipo de error; vacio si termino bien",
                    example = "LazyInitializationException", nullable = true)
            String error
    ) {
    }
}
