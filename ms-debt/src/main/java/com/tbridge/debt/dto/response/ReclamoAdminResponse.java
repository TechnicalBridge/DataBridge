package com.tbridge.debt.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.hateoas.server.core.Relation;

import java.time.Instant;

/**
 * Un reclamo de un deudor para el panel (#436): de que deuda es, quien tiene
 * que responderlo y cuantos dias lleva, sin datos del deudor. El texto que
 * escribio el deudor va tal cual: el portal lo muestra como texto.
 */
@Relation(collectionRelation = "reclamos", itemRelation = "reclamo")
@Schema(description = "Un reclamo de un deudor, sin sus datos")
public record ReclamoAdminResponse(
        @Schema(description = "La deuda reclamada", example = "15") Long deuda,
        @Schema(description = "La referencia que le puso el acreedor", example = "CTR-2024-007") String referencia,
        @Schema(example = "Arriendo local comercial") String concepto,
        @Schema(example = "Patrimonio Inmuebles") String acreedor,
        @Schema(description = "La empresa que la cobra y tiene que responder: la agencia de su campana, o el "
                + "acreedor si no tiene", example = "APOFYX") String cobra,
        @Schema(nullable = true, example = "Arriendos septiembre") String campana,
        @Schema(description = "no_reconoce, ya_pagada, monto_incorrecto u otro", example = "ya_pagada") String motivo,
        @Schema(description = "Lo que escribio el deudor, tal cual", nullable = true,
                example = "Pague agosto en la oficina el 10 de septiembre") String detalle,
        @Schema(description = "Cuando reclamo") Instant abierto,
        @Schema(description = "Abierto: los dias que lleva sin respuesta. Resuelto: los que tardo en resolverse. En "
                + "dias de Chile", example = "4") long dias,
        @Schema(description = "Cuando se cerro. Vacio si sigue abierto", nullable = true) Instant resuelto,
        @Schema(description = "reanudada (la deuda correspondia y volvio a cobranza) o retirada (salio de la "
                + "cobranza). Vacio si sigue abierto", nullable = true, example = "retirada") String resultado,
        @Schema(description = "Si se retiro, con que motivo: disputa_resuelta si la empresa le dio la razon al "
                + "deudor, u otro si la retiro en una carga", nullable = true, example = "disputa_resuelta")
        String motivoRetiro,
        @Schema(description = "Lo que anoto la empresa al resolverlo", nullable = true,
                example = "El pago estaba en la oficina") String nota
) {
}
