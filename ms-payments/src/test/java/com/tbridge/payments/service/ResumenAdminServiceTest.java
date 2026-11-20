package com.tbridge.payments.service;

import com.tbridge.payments.model.Payment;
import com.tbridge.payments.repository.ConteoPorPasarela;
import com.tbridge.payments.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResumenAdminServiceTest {
    @Test
    void arranca_como_servicio_spring_con_repositorio() {
        new ApplicationContextRunner()
                .withBean(PaymentRepository.class, () -> mock(PaymentRepository.class))
                .withUserConfiguration(ResumenAdminService.class)
                .run(context -> {
                    org.junit.jupiter.api.Assertions.assertNull(context.getStartupFailure());
                    org.junit.jupiter.api.Assertions.assertNotNull(context.getBean(ResumenAdminService.class));
                });
    }

    @Test
    void usa_dias_chilenos_y_muestra_ceros_para_pasarelas_sin_pagos() {
        var pagos = mock(PaymentRepository.class);
        // En UTC ya es el día siguiente; en Chile aún es 16 de noviembre.
        var ahora = Instant.parse("2026-11-17T01:00:00Z");
        var inicioDia = Instant.parse("2026-11-16T03:00:00Z");
        var finDia = Instant.parse("2026-11-17T03:00:00Z");
        var inicioMes = Instant.parse("2026-11-01T03:00:00Z");
        var finMes = Instant.parse("2026-12-01T03:00:00Z");
        when(pagos.pagosConfirmadosPorPasarela(inicioDia, finDia))
                .thenReturn(List.of(new ConteoPorPasarela(Payment.Gateway.webpay, 3)));
        when(pagos.pagosConfirmadosPorPasarela(inicioMes, finMes))
                .thenReturn(List.of(new ConteoPorPasarela(Payment.Gateway.webpay, 20)));
        when(pagos.countByStatusAndCreatedAtBefore(Payment.Status.created,
                Instant.parse("2026-11-17T00:00:00Z"))).thenReturn(2L);

        var resultado = new ResumenAdminService(pagos, Clock.fixed(ahora, ZoneOffset.UTC)).resumen();
        assertEquals(3, resultado.pagosHoy().get("webpay"));
        assertEquals(0, resultado.pagosHoy().get("khipu"));
        assertEquals(20, resultado.pagosMes().get("webpay"));
        assertEquals(2, resultado.abiertosMasDeUnaHora());
    }
}
