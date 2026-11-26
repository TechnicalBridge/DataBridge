package com.tbridge.auth.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.hateoas.server.core.Relation;

import java.time.Instant;

/**
 * Una sesion abierta de alguien del personal, para el panel (#439): quien, de
 * que empresa, desde cuando y hasta cuando puede renovarse. Sin la IP, sin la
 * huella de la llave y sin la llave: con la familia solo se puede cerrar.
 */
@Relation(collectionRelation = "sesiones", itemRelation = "sesion")
@Schema(description = "Una sesion abierta de una persona de una empresa o de un admin de DataBridge")
public record SesionAbiertaResponse(
        @Schema(description = "La familia de llaves de la sesion: con ella se cierra",
                example = "6f1c2a0e-8d4b-4c1e-9a7f-2b3c4d5e6f70")
        String familia,
        @Schema(description = "Vacio si la persona ya no tiene cuenta", example = "Camila Reyes", nullable = true)
        String nombre,
        @Schema(example = "camila.reyes@example.com")
        String correo,
        @Schema(description = "admin: de DataBridge. usuario: de una empresa", example = "usuario",
                allowableValues = {"admin", "usuario"})
        String rol,
        @Schema(description = "La empresa de la persona; vacio para un admin", example = "76000000-0", nullable = true)
        String empresaRut,
        @Schema(description = "Cuando entro con su codigo o su enlace")
        Instant desde,
        @Schema(description = "La ultima vez que renovo la sesion; vacio si no la ha renovado", nullable = true)
        Instant renovada,
        @Schema(description = "Hasta cuando puede renovarla sin volver a entrar")
        Instant vence,
        @Schema(description = "Si es una sesion del mismo admin que consulta")
        boolean tuya,
        @Schema(description = "Si se cierra, cuantos minutos puede durar, a lo mas, el acceso que la persona ya tiene: "
                + "lo que dura el JWT (JWT_TTL_MINUTES)", example = "15")
        long minutosDeAcceso
) {
}
