package com.tbridge.debt.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.debt.model.Batch;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.DebtEvent;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.DebtEventRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;

class ReclamosAdminServiceTest {
    @Test
    void usa_la_ultima_disputa_abierta_y_cuenta_dias_chilenos_sin_interpretar_texto() {
        var repo = mock(DebtEventRepository.class);
        var deuda = new Debt();
        deuda.setId(5L);
        deuda.setExternalId("contrato-1");
        var empresa = new Organization();
        empresa.setTradeName("APOFYX");
        var lote = new Batch();
        lote.setSender(empresa);
        deuda.setLastBatch(lote);
        var anterior = evento(deuda, "2026-11-12T14:00:00Z", "ya_pagada", "{\"detalle\":\"antes\"}");
        var ultimo = evento(deuda, "2026-11-16T23:00:00Z", "monto_incorrecto", "{\"detalle\":\"<b>texto</b>\"}");
        when(repo.reclamosAbiertos()).thenReturn(List.of(anterior, ultimo));
        when(repo.reclamosResueltos(any(), any())).thenReturn(List.of());
        var servicio = new ReclamosAdminService(repo, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-11-18T01:00:00Z"), ZoneOffset.UTC));

        var resultado = servicio.consultar(null, null);
        assertEquals(1, resultado.abiertos().size());
        assertEquals("monto_incorrecto", resultado.abiertos().getFirst().motivo());
        assertEquals("<b>texto</b>", resultado.abiertos().getFirst().detalle());
        assertEquals(1, resultado.abiertos().getFirst().diasSinRespuesta());
    }

    @Test
    void rechaza_periodo_invertido() {
        var servicio = new ReclamosAdminService(mock(DebtEventRepository.class), new ObjectMapper(),
                Clock.systemUTC());
        assertThrows(com.tbridge.debt.exception.ApiException.class,
                () -> servicio.consultar(LocalDate.of(2026, 11, 18), LocalDate.of(2026, 11, 1)));
    }

    @Test
    void resueltos_asocian_cada_resolucion_con_el_reclamo_anterior_y_respetan_el_dia_hasta() {
        var repo = mock(DebtEventRepository.class);
        var deuda = new Debt();
        deuda.setId(5L);
        deuda.setExternalId("contrato-1");
        var empresa = new Organization();
        empresa.setTradeName("APOFYX");
        var lote = new Batch();
        lote.setSender(empresa);
        deuda.setLastBatch(lote);
        var primero = evento(deuda, "2026-11-02T10:00:00Z", "ya_pagada",
                "{\"detalle\":\"Transferencia\"}");
        var reanudado = DebtEvent.de(deuda, DebtEvent.Type.updated, DebtEvent.Actor.agency)
                .conReferencia("disputa_rechazada").conDetalle("{\"nota\":\"No consta el pago\"}");
        reanudado.setOccurredAt(Instant.parse("2026-11-03T10:00:00Z"));
        var segundo = evento(deuda, "2026-11-17T10:00:00Z", "monto_incorrecto",
                "{\"detalle\":\"Monto distinto\"}");
        var retirado = DebtEvent.de(deuda, DebtEvent.Type.withdrawn, DebtEvent.Actor.agency)
                .conReferencia("disputa_resuelta");
        retirado.setOccurredAt(Instant.parse("2026-11-19T02:30:00Z")); // 18 en Chile
        when(repo.reclamosAbiertos()).thenReturn(List.of());
        when(repo.reclamosResueltos(any(), any())).thenReturn(List.of(retirado, reanudado));
        when(repo.findByDebtInAndType(any(), eq(DebtEvent.Type.disputed)))
                .thenReturn(List.of(primero, segundo));
        var servicio = new ReclamosAdminService(repo, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-11-19T03:00:00Z"), ZoneOffset.UTC));

        var resultado = servicio.consultar(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 18));

        verify(repo).reclamosResueltos(Instant.parse("2026-11-01T03:00:00Z"),
                Instant.parse("2026-11-19T03:00:00Z"));
        assertEquals(2, resultado.resueltos().size());
        assertEquals("monto_incorrecto", resultado.resueltos().get(0).motivo());
        assertEquals("retirado", resultado.resueltos().get(0).resultado());
        assertEquals("ya_pagada", resultado.resueltos().get(1).motivo());
        assertEquals("reanudado", resultado.resueltos().get(1).resultado());
        assertEquals("No consta el pago", resultado.resueltos().get(1).nota());
    }

    private static DebtEvent evento(Debt deuda, String cuando, String motivo, String detalle) {
        var e = DebtEvent.de(deuda, DebtEvent.Type.disputed, DebtEvent.Actor.debtor)
                .conReferencia(motivo).conDetalle(detalle);
        e.setOccurredAt(Instant.parse(cuando));
        return e;
    }
}
