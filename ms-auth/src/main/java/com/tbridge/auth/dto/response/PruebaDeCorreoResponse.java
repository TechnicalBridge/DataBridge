package com.tbridge.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** Como le fue a un correo de prueba: si salio, a donde llego de verdad, y si no, por que. */
@Schema(description = "El resultado de mandar un correo de prueba con la configuracion que tiene el servicio")
public record PruebaDeCorreoResponse(
        @Schema(description = "Si el servidor de correo lo acepto", example = "true") boolean enviado,
        @Schema(description = "Para quien se pidio", example = "deudor@correo.cl") String para,
        @Schema(description = "A donde salio de verdad: en modo de prueba puede ser el buzon de desvio",
                example = "equipo@gmail.com", nullable = true) String llegaA,
        @Schema(description = "El servidor y el puerto que se usaron. Nunca la clave", example = "smtp.gmail.com:587",
                nullable = true) String servidor,
        @Schema(description = "Con que remitente salio", example = "DataBridge <equipo@gmail.com>") String remitente,
        @Schema(description = "A quien se le puede escribir",
                example = "modo de prueba: directo solo a equipo@gmail.com; lo demas se desvia a equipo@gmail.com")
        String modo,
        @Schema(description = "Por que no salio, como lo dijo el servidor", nullable = true) String error
) {
}
