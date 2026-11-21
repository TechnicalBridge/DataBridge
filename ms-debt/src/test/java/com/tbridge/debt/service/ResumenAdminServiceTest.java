package com.tbridge.debt.service;

import com.tbridge.debt.dto.response.CampanaAdminResponse;
import com.tbridge.debt.dto.response.ComisionesResponse;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.ContactRepository;
import com.tbridge.debt.repository.ContactosPorCampana;
import com.tbridge.debt.repository.ConteoPorEstado;
import com.tbridge.debt.repository.DebtRepository;
import com.tbridge.debt.repository.CampaignRepository;
import com.tbridge.debt.repository.ConteoPorCampana;
import com.tbridge.debt.model.Campaign;
import com.tbridge.debt.repository.OrganizationRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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

        var resultado = new ResumenAdminService(empresas, deudas, comisiones, mock(CampaignRepository.class),
                mock(ContactRepository.class)).resumen();
        assertEquals(4, resultado.empresasActivas());
        assertEquals(8, resultado.deudasPorEstado().get("open"));
        assertEquals(0, resultado.deudasPorEstado().get("paid"));
        assertEquals(15000, resultado.recuperado());
        assertEquals(1200, resultado.comision());
    }

    @Test
    void campanas_solo_exponen_totales_y_sus_contactos_de_hoy_y_de_la_semana() {
        var repositorio = mock(CampaignRepository.class);
        var contactos = mock(ContactRepository.class);
        when(repositorio.contarParaAdmin()).thenReturn(List.of(
                new ConteoPorCampana(1L, "Cobranza otoño", "Agencia", "Acreedor", Campaign.Status.running,
                        LocalDate.of(2026, 11, 1), null, 5, 3),
                new ConteoPorCampana(2L, "Sin contactos", "Agencia", "Acreedor", Campaign.Status.paused,
                        LocalDate.of(2026, 10, 1), LocalDate.of(2026, 12, 31), 2, 2)));
        when(contactos.porCampanaDesde(any(), any())).thenReturn(List.of(new ContactosPorCampana(1L, 2, 7)));
        //  Viernes 20 de noviembre de 2026, 1:30 en Chile (4:30 en UTC): hoy sigue siendo el 20 en Chile.
        var reloj = Clock.fixed(Instant.parse("2026-11-20T04:30:00Z"), ZoneOffset.UTC);
        var servicio = new ResumenAdminService(mock(OrganizationRepository.class), mock(DebtRepository.class),
                mock(ComisionService.class), repositorio, contactos, reloj);

        List<CampanaAdminResponse> campanas = servicio.campanas();

        CampanaAdminResponse otono = campanas.getFirst();
        assertEquals(5, otono.deudas());
        assertEquals(3, otono.deudasAbiertas());
        assertEquals("running", otono.estado());
        assertEquals("Acreedor", otono.acreedor());
        assertNull(otono.hasta());
        assertEquals(2, otono.contactosHoy());
        assertEquals(7, otono.contactosSemana());
        assertEquals(0, campanas.get(1).contactosHoy(), "sin contactos, ceros");
        assertEquals(0, campanas.get(1).contactosSemana());
        //  La semana del limite de la ley: los ultimos siete dias, hoy incluido, desde la medianoche de Chile.
        verify(contactos).porCampanaDesde(Instant.parse("2026-11-14T03:00:00Z"), Instant.parse("2026-11-20T03:00:00Z"));
    }
}
