package com.tbridge.payments.service;

import com.tbridge.payments.dto.response.ResumenAdminResponse;
import com.tbridge.payments.model.Payment;
import com.tbridge.payments.repository.ConteoPorPasarela;
import com.tbridge.payments.repository.PaymentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class ResumenAdminService {
    private static final ZoneId CHILE = ZoneId.of("America/Santiago");
    private final PaymentRepository pagos;
    private final Clock reloj;

    @Autowired
    public ResumenAdminService(PaymentRepository pagos) {
        this(pagos, Clock.systemUTC());
    }

    ResumenAdminService(PaymentRepository pagos, Clock reloj) {
        this.pagos = pagos;
        this.reloj = reloj;
    }

    public ResumenAdminResponse resumen() {
        var ahora = reloj.instant();
        LocalDate hoy = ahora.atZone(CHILE).toLocalDate();
        Map<String, Long> dia = contar(hoy.atStartOfDay(CHILE).toInstant(),
                hoy.plusDays(1).atStartOfDay(CHILE).toInstant());
        Map<String, Long> mes = contar(hoy.withDayOfMonth(1).atStartOfDay(CHILE).toInstant(),
                hoy.withDayOfMonth(1).plusMonths(1).atStartOfDay(CHILE).toInstant());
        return new ResumenAdminResponse(hoy, dia, mes,
                pagos.countByStatusAndCreatedAtBefore(Payment.Status.created, ahora.minus(Duration.ofHours(1))));
    }

    private Map<String, Long> contar(java.time.Instant desde, java.time.Instant hasta) {
        Map<String, Long> resultado = new LinkedHashMap<>();
        for (Payment.Gateway pasarela : Payment.Gateway.values()) {
            resultado.put(pasarela.name(), 0L);
        }
        for (ConteoPorPasarela fila : pagos.pagosConfirmadosPorPasarela(desde, hasta)) {
            resultado.put(fila.pasarela().name(), fila.pagos());
        }
        return resultado;
    }
}
