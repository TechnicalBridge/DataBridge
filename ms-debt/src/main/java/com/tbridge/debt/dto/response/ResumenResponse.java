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
        @Schema(description = "En revision: el deudor reclamo y espera la respuesta de la empresa. Tambien cuentan "
                + "en activas", example = "1") int enRevision,
        List<PorMoneda> porMoneda,
        List<PorAcreedor> porAcreedor,
        @Schema(description = "Lo recuperado cada dia de los ultimos 30, una serie por moneda. Con ceros: un grafico "
                + "que se salta los dias vacios muestra una tendencia que no existe")
        Map<String, List<PuntoDiario>> recuperadoPorDia
) {

    @Schema(description = "Saldo y recuperado en una moneda")
    public record PorMoneda(
            @Schema(example = "CLP") String moneda,
            @Schema(example = "1040000") BigDecimal saldo,
            @Schema(example = "410000") BigDecimal recuperado,
            @Schema(description = "Porcentaje recuperado, con un decimal", example = "28.3") BigDecimal tasaRecuperacion
    ) {
    }

    @Schema(description = "Saldo y recuperado de un acreedor, en una moneda")
    public record PorAcreedor(
            @Schema(example = "Patrimonio Inmuebles") String acreedor,
            @Schema(example = "CLP") String moneda,
            @Schema(example = "1040000") BigDecimal saldo,
            @Schema(example = "410000") BigDecimal recuperado
    ) {
        public PorAcreedor sumar(BigDecimal otroSaldo, BigDecimal otroRecuperado) {
            return new PorAcreedor(acreedor, moneda, saldo.add(otroSaldo), recuperado.add(otroRecuperado));
        }
    }

    @Schema(description = "Lo que entro un dia")
    public record PuntoDiario(
            @Schema(example = "2026-09-23") String dia,
            @Schema(example = "410000") BigDecimal monto
    ) {
    }
}
