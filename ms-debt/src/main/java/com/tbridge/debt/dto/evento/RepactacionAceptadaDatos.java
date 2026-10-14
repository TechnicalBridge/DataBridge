package com.tbridge.debt.dto.evento;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;

import java.math.BigDecimal;

/**
 * {@code repactacion.aceptada}: el deudor acepto un plan de cuotas.
 *
 * <p>Si la deuda genera intereses, van la tasa, lo repactado (el capital mas la
 * mora de ese dia) y el total a pagar con el interes del convenio. Sin tasa,
 * esos tres no vienen.
 */
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record RepactacionAceptadaDatos(
        String deudaIdExterno,
        int cuotas,
        Number montoCuota,
        String moneda,
        String primeraCuota,
        BigDecimal tasaInteresMensual,
        Number aRepactar,
        Number totalAPagar,
        BigDecimal cae
) {
    public RepactacionAceptadaDatos(String deudaIdExterno, int cuotas, Number montoCuota, String moneda,
                                   String primeraCuota, BigDecimal tasaInteresMensual, Number aRepactar,
                                   Number totalAPagar) {
        this(deudaIdExterno, cuotas, montoCuota, moneda, primeraCuota, tasaInteresMensual, aRepactar, totalAPagar, null);
    }
}
