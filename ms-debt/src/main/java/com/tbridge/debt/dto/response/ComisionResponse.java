package com.tbridge.debt.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Lo que una empresa le paga a DataBridge en un mes (#200): lo recuperado (G),
 * el porcentaje de su tramo, la comision P(G) = G · p(G) y lo que le queda.
 * Los montos son pesos enteros; el porcentaje, un numero; el mes, una fecha.
 */
@Schema(description = "El modelo de negocio aplicado a un mes de una empresa")
public record ComisionResponse(
        @Schema(description = "El mes, como su primer día", example = "2026-10-01") LocalDate mes,
        @Schema(description = "G: lo recuperado en el mes, en pesos", example = "1420000") long recuperado,
        @Schema(description = "Cuántos pagos se abonaron en el mes", example = "4") int pagos,
        @Schema(description = "p(G), en porcentaje", example = "10") BigDecimal porcentaje,
        @Schema(description = "P(G) = G · p(G): lo que la empresa le paga a DataBridge, en pesos", example = "142000")
        long comision,
        @Schema(description = "G − P(G): lo que le queda a la empresa, en pesos", example = "1278000")
        long paraLaEmpresa,
        @Schema(description = "Lo recuperado por cada campaña. El porcentaje lo decide el total del mes")
        List<PorCampana> porCampana,
        @Schema(description = "Los tramos del modelo, con el que rige este mes marcado") List<TramoResponse> tramos
) {

    public record PorCampana(
            @Schema(example = "CMP-20261015-KHJL") String idExterno,
            @Schema(example = "Arriendos octubre") String nombre,
            @Schema(example = "1050000") long recuperado,
            @Schema(example = "3") int pagos
    ) {
    }

    public record TramoResponse(
            @Schema(description = "Desde cuánto, sin incluirlo", example = "1000000") long desde,
            @Schema(description = "Hasta cuánto, incluido. Vacío en el último", example = "5000000") Long hasta,
            @Schema(example = "10") BigDecimal porcentaje,
            @Schema(description = "Si es el tramo de este mes") boolean vigente
    ) {
    }
}
