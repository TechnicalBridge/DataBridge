package com.tbridge.debt.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.hateoas.server.core.Relation;

import java.time.Instant;
import java.util.List;

/**
 * Una suscripcion de avisos para el panel (#438): de que empresa es, a donde
 * avisa y como van sus entregas. Nunca el secreto, y la URL sin credenciales.
 */
@Relation(collectionRelation = "suscripciones", itemRelation = "suscripcion")
@Schema(description = "Una suscripcion de avisos de una empresa, sin su secreto")
public record SuscripcionAdminResponse(
        @Schema(example = "1") Long id,
        @Schema(example = "APOFYX") String empresa,
        @Schema(example = "77305118-6") String empresaRut,
        @Schema(description = "A donde se avisa, sin usuario, clave ni parametros",
                example = "https://apofyx.cl/api/eventos") String url,
        @Schema(description = "Los eventos que recibe. Vacio: todos") List<String> eventos,
        @Schema(description = "Si se le encolan avisos nuevos y se le entregan los pendientes") boolean activa,
        Instant creada,
        @Schema(description = "La ultima entrega que el receptor acepto", nullable = true) Instant ultimaEntrega,
        @Schema(description = "Avisos que esperan su proximo intento", example = "0") long pendientes,
        @Schema(description = "Avisos que se rindieron: se reintentan desde Operacion", example = "0") long fallidas
) {
}
