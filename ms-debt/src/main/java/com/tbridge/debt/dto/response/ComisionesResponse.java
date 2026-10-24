package com.tbridge.debt.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Lo que DataBridge le cobra a cada empresa en un mes (#200): lo que ve el admin en su panel. */
@Schema(description = "La comisión de cada empresa usuaria en un mes")
public record ComisionesResponse(
        @Schema(description = "El mes, como su primer día", example = "2026-10-01") LocalDate mes,
        @Schema(description = "Cada empresa con pagos en el mes, de la que más paga a la que menos") List<Empresa> empresas,
        @Schema(description = "Lo recuperado por todas, en pesos", example = "3200000") long recuperado,
        @Schema(description = "Lo que DataBridge cobra en total, en pesos", example = "296000") long comision
) {

    public record Empresa(
            @Schema(example = "77305118-6") String rut,
            @Schema(example = "APOFYX") String nombre,
            @Schema(description = "G, en pesos", example = "1420000") long recuperado,
            @Schema(example = "4") int pagos,
            @Schema(description = "p(G)", example = "10") BigDecimal porcentaje,
            @Schema(description = "P(G), en pesos", example = "142000") long comision,
            @Schema(description = "G − P(G), en pesos", example = "1278000") long paraLaEmpresa
    ) {
    }
}
