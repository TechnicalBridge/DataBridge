package com.tbridge.debt.dto.response;

import java.time.LocalDate;
import java.util.Map;

/** Totales globales, sin datos de deudores ni duplicar carteras de agencias. */
public record ResumenAdminResponse(long empresasActivas, long empresasSuspendidas,
                                   Map<String, Long> deudasPorEstado, LocalDate mes,
                                   long recuperado, long comision) {
}
