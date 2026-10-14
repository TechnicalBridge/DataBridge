package com.tbridge.debt.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

/**
 * Un plan de cuotas: lo que se repacta (el capital mas la mora de hoy) y como
 * se paga. Sin tasa, es sin interes y el total es lo repactado; con tasa, las
 * cuotas son de sistema frances. La ultima cuota absorbe el redondeo.
 */
@Schema(description = "El plan de cuotas para el saldo de una deuda")
public record RepactPlan(
        @Schema(example = "6") int months,
        @Schema(description = "Cada cuota salvo la ultima", example = "19.25") BigDecimal monthlyAmount,
        @Schema(description = "La ultima, con el resto del redondeo", example = "19.25") BigDecimal lastAmount,
        @Schema(description = "Lo que se paga en total: lo repactado, mas el interes del convenio si hay tasa",
                example = "115.50") BigDecimal total,
        List<InstallmentPreview> cuotas,
        @Schema(description = "El capital pendiente", example = "112.00") BigDecimal capital,
        @Schema(description = "La mora acumulada hasta hoy, que se suma a lo repactado", example = "3.50")
        BigDecimal interesMora,
        @Schema(description = "Lo que se repacta: el capital mas la mora", example = "115.50") BigDecimal aRepactar,
        @Schema(description = "La tasa mensual del acreedor, en porcentaje. Vacia si la deuda no genera intereses",
                nullable = true, example = "1.5") BigDecimal tasaInteresMensual,
        @Schema(description = "Lo que suman los intereses del convenio", example = "0") BigDecimal interesConvenio,
        @Schema(description = "La Carga Anual Equivalente (CAE) del convenio según Ley 20.555 y D.S. 43/2012, en porcentaje con dos decimales",
                example = "18.00") BigDecimal cae,
        @Schema(description = "El costo total del convenio: la suma de todas las cuotas",
                example = "962685") BigDecimal costoTotal
) {
    public RepactPlan(int months, BigDecimal monthlyAmount, BigDecimal lastAmount, BigDecimal total,
                      List<InstallmentPreview> cuotas, BigDecimal capital, BigDecimal interesMora,
                      BigDecimal aRepactar, BigDecimal tasaInteresMensual, BigDecimal interesConvenio) {
        this(months, monthlyAmount, lastAmount, total, cuotas, capital, interesMora, aRepactar, tasaInteresMensual,
                interesConvenio, BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), total);
    }
}
