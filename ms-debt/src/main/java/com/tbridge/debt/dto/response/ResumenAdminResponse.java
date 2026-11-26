package com.tbridge.debt.dto.response;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Totales globales, sin datos de deudores ni duplicar carteras de agencias.
 *
 * @param recuperadoPorDia lo que entro cada uno de los ultimos 30 dias en Chile, en pesos, con ceros en los dias
 *                         sin pagos (#544).
 */
public record ResumenAdminResponse(long empresasActivas, long empresasSuspendidas,
                                   Map<String, Long> deudasPorEstado, LocalDate mes,
                                   long recuperado, long comision, List<PuntoDiario> recuperadoPorDia) {

    /** Un dia: {@code 2026-11-26} y los pesos que entraron. */
    public record PuntoDiario(LocalDate dia, long pesos) {
    }

    /** Sin la serie diaria. */
    public ResumenAdminResponse(long empresasActivas, long empresasSuspendidas, Map<String, Long> deudasPorEstado,
                                LocalDate mes, long recuperado, long comision) {
        this(empresasActivas, empresasSuspendidas, deudasPorEstado, mes, recuperado, comision, List.of());
    }
}
