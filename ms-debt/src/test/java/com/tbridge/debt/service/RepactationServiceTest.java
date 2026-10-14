package com.tbridge.debt.service;

import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.dto.response.RepactPlan;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RepactationServiceTest {

    private final RepactationService service = new RepactationService();

    @Test
    void splitsRemainderIntoLastInstallment() {
        RepactPlan plan = service.simulate(new BigDecimal("100000"), Debt.Currency.CLP, 3, LocalDate.of(2026, 1, 15));
        assertEquals(3, plan.cuotas().size());
        BigDecimal sum = plan.cuotas().stream()
                .map(c -> c.amount())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("100000"), sum);
        assertEquals(LocalDate.of(2026, 1, 15), plan.cuotas().getFirst().dueDate());
        assertEquals(LocalDate.of(2026, 3, 15), plan.cuotas().getLast().dueDate());
    }

    @Test
    void rejectsOutOfRangeMonths() {
        assertThrows(ApiException.class, () -> service.simulate(new BigDecimal("100000"), Debt.Currency.CLP, 2, LocalDate.now()));
        assertThrows(ApiException.class, () -> service.simulate(new BigDecimal("100000"), Debt.Currency.CLP, 36, LocalDate.now()));
    }

    /**
     * Una deuda en UF se reparte en centesimas y el total no cambia. Antes se
     * redondeaba a UF enteras: 115,50 pasaba a 116.
     */
    @Test
    void enUfNoCambiaElMontoDeLaDeuda() {
        RepactPlan plan = service.simulate(new BigDecimal("115.50"), Debt.Currency.UF, 6, LocalDate.of(2026, 10, 20));
        assertEquals(new BigDecimal("115.50"), plan.total());
        assertEquals(new BigDecimal("19.25"), plan.monthlyAmount());
        BigDecimal suma = plan.cuotas().stream().map(c -> c.amount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("115.50"), suma);
    }

    @Test
    void enUfElRestoVaALaUltimaCuota() {
        RepactPlan plan = service.simulate(new BigDecimal("100.00"), Debt.Currency.UF, 3, LocalDate.of(2026, 10, 20));
        assertEquals(new BigDecimal("33.33"), plan.monthlyAmount());
        assertEquals(new BigDecimal("33.34"), plan.lastAmount());
    }

    @Test
    void con_tasa_las_cuotas_son_de_sistema_frances() {
        //  100.000 al 1% mensual en 3 cuotas: 100.000 x 0,01 / (1 - 1,01^-3) = 34.002,21
        RepactPlan plan = service.simulate(new BigDecimal("100000"), BigDecimal.ZERO, new BigDecimal("1"),
                Debt.Currency.CLP, 3, LocalDate.of(2026, 11, 6));

        assertEquals(new BigDecimal("34002"), plan.monthlyAmount());
        assertEquals(List.of("1000", "670", "337"),
                plan.cuotas().stream().map(c -> c.interest().toPlainString()).toList(),
                "el interes de cada mes, sobre lo que queda por pagar");
        BigDecimal capital = plan.cuotas().stream().map(c -> c.amount().subtract(c.interest()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(new BigDecimal("100000"), capital, "las cuotas pagan justo lo repactado");
        assertEquals(new BigDecimal("34003"), plan.lastAmount(), "la ultima absorbe el redondeo");
        assertEquals(new BigDecimal("2007"), plan.interesConvenio());
        assertEquals(new BigDecimal("102007"), plan.total());
        assertEquals(new BigDecimal("1"), plan.tasaInteresMensual());
    }

    @Test
    void la_mora_se_suma_a_lo_que_se_repacta() {
        RepactPlan plan = service.simulate(new BigDecimal("100000"), new BigDecimal("4100"), null,
                Debt.Currency.CLP, 3, LocalDate.of(2026, 11, 6));

        assertEquals(new BigDecimal("104100"), plan.aRepactar());
        assertEquals(new BigDecimal("104100"), plan.total(), "sin tasa el convenio no suma intereses");
        assertEquals(new BigDecimal("4100"), plan.interesMora());
        assertNull(plan.tasaInteresMensual());
    }

    @Test
    void cae_y_costo_total_segun_criterio_de_aceptacion() {
        // $914.100 al 1,5% mensual en 6 cuotas: el deudor ve CAE 18,00% y costo total $962.685
        RepactPlan plan = service.simulate(new BigDecimal("914100"), BigDecimal.ZERO, new BigDecimal("1.5"),
                Debt.Currency.CLP, 6, LocalDate.of(2026, 11, 1));

        assertEquals(new BigDecimal("18.00"), plan.cae());
        assertEquals(new BigDecimal("962685"), plan.costoTotal());
        assertEquals(new BigDecimal("962685"), plan.total());
    }

    @Test
    void sin_tasa_la_cae_es_cero() {
        RepactPlan plan = service.simulate(new BigDecimal("100000"), Debt.Currency.CLP, 3, LocalDate.of(2026, 1, 15));
        assertEquals(new BigDecimal("0.00"), plan.cae());
        assertEquals(new BigDecimal("100000"), plan.costoTotal());
    }

    @Test
    void cae_en_uf_con_tasa_y_sin_tasa() {
        RepactPlan sinTasa = service.simulate(new BigDecimal("115.50"), Debt.Currency.UF, 6, LocalDate.of(2026, 10, 20));
        assertEquals(new BigDecimal("0.00"), sinTasa.cae());
        assertEquals(new BigDecimal("115.50"), sinTasa.costoTotal());

        RepactPlan conTasa = service.simulate(new BigDecimal("100.00"), BigDecimal.ZERO, new BigDecimal("1.5"),
                Debt.Currency.UF, 6, LocalDate.of(2026, 10, 20));
        assertEquals(new BigDecimal("18.01"), conTasa.cae());
        assertEquals(new BigDecimal("105.32"), conTasa.costoTotal());
    }
}
