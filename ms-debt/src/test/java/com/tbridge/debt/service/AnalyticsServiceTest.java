package com.tbridge.debt.service;

import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.DebtEvent;
import com.tbridge.debt.model.Installment;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.model.Repactation;
import com.tbridge.debt.repository.DebtEventRepository;
import com.tbridge.debt.repository.DebtRepository;
import com.tbridge.debt.repository.InstallmentRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnalyticsServiceTest {
    private final DebtRepository debts = mock(DebtRepository.class);
    private final DebtEventRepository eventos = mock(DebtEventRepository.class);
    private final InstallmentRepository cuotas = mock(InstallmentRepository.class);
    private final DebtService deudas = new DebtService(debts, null, null, null, cuotas, null,
            eventos, null, null, null, null, null);
    private final AnalyticsService analytics = new AnalyticsService(debts, eventos, deudas);
    private final Organization empresa = new Organization();

    @Test
    void un_pago_con_intereses_supera_el_monto_original_sin_inflar_el_porcentaje_de_cartera() {
        Debt deuda = deuda(Debt.Status.paid);
        when(cuotas.findByDebtAndStatus(deuda, Installment.Status.pending)).thenReturn(List.of());
        DebtEvent pago = DebtEvent.de(deuda, DebtEvent.Type.payment_applied, DebtEvent.Actor.system)
                .conMonto(new BigDecimal("2300000"), Debt.Currency.CLP);
        pago.setOccurredAt(Instant.now().minus(1, ChronoUnit.DAYS));
        when(eventos.findByDebtInAndTypeAndOccurredAtAfter(eq(List.of(deuda)),
                eq(DebtEvent.Type.payment_applied), any())).thenReturn(List.of(pago));

        var resumen = analytics.resumen(empresa);
        var pesos = resumen.porMoneda().getFirst();
        assertEquals(BigDecimal.ZERO, pesos.saldo());
        assertEquals(new BigDecimal("2000000"), pesos.recuperado());
        assertEquals(new BigDecimal("100.0"), pesos.tasaRecuperacion());
        assertEquals(new BigDecimal("2300000"), resumen.recuperadoPorDia().get("CLP").stream()
                .map(punto -> punto.monto()).reduce(BigDecimal.ZERO, BigDecimal::add));
    }

    @Test
    void las_cuotas_pendientes_con_intereses_pueden_dejar_una_diferencia_negativa() {
        Debt deuda = deuda(Debt.Status.repacted);
        Installment cuota = new Installment();
        cuota.setDebt(deuda);
        cuota.setRepactation(new Repactation());
        cuota.setStatus(Installment.Status.pending);
        cuota.setAmount(new BigDecimal("2300000"));
        when(cuotas.findByDebtAndStatus(deuda, Installment.Status.pending)).thenReturn(List.of(cuota));

        var resumen = analytics.resumen(empresa);
        var pesos = resumen.porMoneda().getFirst();
        assertEquals(new BigDecimal("2300000"), pesos.saldo());
        assertEquals(new BigDecimal("-300000"), pesos.recuperado());
        assertEquals(new BigDecimal("-15.0"), pesos.tasaRecuperacion());
        assertEquals(new BigDecimal("-300000"), resumen.porAcreedor().getFirst().recuperado());
    }

    private Debt deuda(Debt.Status estado) {
        empresa.setRut("77305118-6");
        empresa.setTradeName("Empresa de prueba");
        Debt deuda = new Debt();
        deuda.setCreditor(empresa);
        deuda.setCurrency(Debt.Currency.CLP);
        deuda.setOriginalAmount(new BigDecimal("2000000"));
        deuda.setStatus(estado);
        when(debts.carteraDe(empresa)).thenReturn(List.of(deuda));
        return deuda;
    }
}
