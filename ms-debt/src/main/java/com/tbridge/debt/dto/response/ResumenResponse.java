package com.tbridge.debt.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * Los numeros de UNA cartera: la de la empresa que pregunta.
 *
 * <p>Pesos y UF van siempre por separado: una suma de ambos no significaria nada.
 */
@Schema(description = "El resumen de la cartera de la empresa de la sesion")
public record ResumenResponse(
        @Schema(example = "APOFYX") String organizacion,
        @Schema(example = "77305118-6") String organizacionRut,
        @Schema(description = "Todas las deudas de la cartera", example = "3") int deudas,
        @Schema(description = "En gestion, incluidas las que estan en convenio", example = "2") int activas,
        @Schema(example = "1") int enConvenio,
        @Schema(example = "1") int pagadas,
        @Schema(example = "0") int retiradas,
        List<PorMoneda> porMoneda,
        List<PorAcreedor> porAcreedor,
        @Schema(description = "Suma diaria de pagos aplicados en los últimos 30 días, por moneda. "
                + "Puede incluir intereses y muestra cero en los días sin pagos")
        Map<String, List<PuntoDiario>> recuperadoPorDia
) {

    @Schema(description = "Saldo y capital recuperado en una moneda")
    public record PorMoneda(
            @Schema(example = "CLP") String moneda,
            @Schema(example = "1040000") BigDecimal saldo,
            @Schema(description = "Capital original menos saldo pendiente", example = "410000") BigDecimal recuperado,
            @Schema(description = "Capital recuperado como porcentaje del capital original", example = "28.3") BigDecimal tasaRecuperacion
    ) {
    }

    @Schema(description = "Saldo y capital recuperado de un acreedor, en una moneda")
    public record PorAcreedor(
            @Schema(example = "Patrimonio Inmuebles") String acreedor,
            @Schema(example = "CLP") String moneda,
            @Schema(example = "1040000") BigDecimal saldo,
            @Schema(description = "Capital original menos saldo pendiente", example = "410000") BigDecimal recuperado
    ) {
        public PorAcreedor sumar(BigDecimal otroSaldo, BigDecimal otroRecuperado) {
            return new PorAcreedor(acreedor, moneda, saldo.add(otroSaldo), recuperado.add(otroRecuperado));
        }
    }

    @Schema(description = "Total de pagos aplicados en un día")
    public record PuntoDiario(
            @Schema(example = "2026-09-23") String dia,
            @Schema(example = "410000") BigDecimal monto
    ) {
    }
}
