package com.tbridge.debt.service;

import com.tbridge.debt.dto.response.ComisionesResponse;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.ConteoPorEstado;
import com.tbridge.debt.repository.DebtRepository;
import com.tbridge.debt.repository.CampaignRepository;
import com.tbridge.debt.repository.ConteoPorCampana;
import com.tbridge.debt.model.Campaign;
import com.tbridge.debt.repository.OrganizationRepository;
import org.junit.jupiter.api.Test;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResumenAdminServiceTest {
    @Test
    void cuenta_deudas_una_vez_y_reutiliza_los_totales_de_comisiones() {
        var empresas = mock(OrganizationRepository.class);
        var deudas = mock(DebtRepository.class);
        var comisiones = mock(ComisionService.class);
        var mes = YearMonth.now(ZoneId.of("America/Santiago"));
        when(empresas.countByStatus(Organization.Status.active)).thenReturn(4L);
        when(empresas.countByStatus(Organization.Status.suspended)).thenReturn(1L);
        when(deudas.contarPorEstado()).thenReturn(List.of(new ConteoPorEstado(Debt.Status.open, 8)));
        when(comisiones.deTodas(mes)).thenReturn(new ComisionesResponse(mes.atDay(1), List.of(), 15000, 1200));

        var resultado = new ResumenAdminService(empresas, deudas, comisiones, mock(CampaignRepository.class)).resumen();
        assertEquals(4, resultado.empresasActivas());
        assertEquals(8, resultado.deudasPorEstado().get("open"));
        assertEquals(0, resultado.deudasPorEstado().get("paid"));
        assertEquals(15000, resultado.recuperado());
        assertEquals(1200, resultado.comision());
    }

    @Test
    void campanas_solo_exponen_totales() {
        var repositorio = mock(CampaignRepository.class);
        when(repositorio.contarParaAdmin()).thenReturn(List.of(
                new ConteoPorCampana(1L, "Cobranza otoño", "Agencia", Campaign.Status.running, 5)));
        var servicio = new ResumenAdminService(mock(OrganizationRepository.class), mock(DebtRepository.class),
                mock(ComisionService.class), repositorio);
        assertEquals(5, servicio.campanas().getFirst().deudas());
        assertEquals("running", servicio.campanas().getFirst().estado());
    }
}
