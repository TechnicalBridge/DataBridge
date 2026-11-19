package com.tbridge.payments.dto.response;

import java.time.LocalDate;
import java.util.Map;

/** Pagos confirmados por pasarela; los abiertos se cuentan aparte. */
public record ResumenAdminResponse(LocalDate hoy, Map<String, Long> pagosHoy,
                                   Map<String, Long> pagosMes, long abiertosMasDeUnaHora) {
}
