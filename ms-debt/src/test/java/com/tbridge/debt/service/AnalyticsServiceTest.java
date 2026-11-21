package com.tbridge.debt.service;

import com.tbridge.debt.dto.response.ResumenResponse;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.DebtEventRepository;
import com.tbridge.debt.repository.DebtRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** El resumen de la cartera de una empresa. */
class AnalyticsServiceTest {

    private final DebtRepository deudas = mock(DebtRepository.class);
    private final DebtEventRepository eventos = mock(DebtEventRepository.class);
    private final DebtService servicio = mock(DebtService.class);
    private final AnalyticsService analytics = new AnalyticsService(deudas, eventos, servicio);

    private static Debt deuda(Debt.Status estado) {
        Organization acreedor = new Organization();
        acreedor.setRut("76418902-7");
        acreedor.setTradeName("Patrimonio Inmuebles");
        Debt d = new Debt();
        d.setStatus(estado);
        d.setCreditor(acreedor);
        d.setCurrency(Debt.Currency.CLP);
        d.setOriginalAmount(new BigDecimal("100000"));
        return d;
    }

    @Test
    void cuenta_las_que_esperan_respuesta_de_un_reclamo_y_siguen_entre_las_activas() {
        Organization apofyx = new Organization();
        apofyx.setRut("77305118-6");
        apofyx.setTradeName("APOFYX");
        when(deudas.carteraDe(apofyx)).thenReturn(List.of(deuda(Debt.Status.open), deuda(Debt.Status.disputed),
                deuda(Debt.Status.disputed), deuda(Debt.Status.repacted), deuda(Debt.Status.paid)));
        when(servicio.saldo(any())).thenReturn(BigDecimal.ZERO);

        ResumenResponse resumen = analytics.resumen(apofyx);

        assertEquals(2, resumen.enRevision());
        assertEquals(4, resumen.activas(), "las en revision siguen en gestion");
        assertEquals(1, resumen.enConvenio());
        assertEquals(1, resumen.pagadas());
    }
}
