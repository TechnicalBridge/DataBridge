package com.tbridge.debt.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.debt.dto.response.ReclamoAdminResponse;
import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.model.Campaign;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.DebtEvent;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.DebtEventRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Los reclamos de todas las empresas en el panel (#436). */
class ReclamosAdminServiceTest {

    /** El 18 de noviembre de 2026 al mediodia de Chile (UTC-3 en verano). */
    private static final Instant AHORA = Instant.parse("2026-11-18T15:00:00Z");

    private final DebtEventRepository eventos = mock(DebtEventRepository.class);
    private final ReclamosAdminService servicio =
            new ReclamosAdminService(eventos, new ObjectMapper(), Clock.fixed(AHORA, ZoneOffset.UTC));

    private static Organization empresa(long id, String nombre) {
        Organization o = new Organization();
        o.setId(id);
        o.setTradeName(nombre);
        return o;
    }

    private static Debt deuda(long id, boolean conCampana) {
        Debt d = new Debt();
        d.setId(id);
        d.setExternalId("CTR-" + id);
        d.setConcept("Arriendo");
        d.setCreditor(empresa(1, "Patrimonio Inmuebles"));
        if (conCampana) {
            Campaign c = new Campaign();
            c.setId(7L);
            c.setName("Arriendos septiembre");
            c.setAgency(empresa(2, "APOFYX"));
            d.setCampaign(c);
        }
        return d;
    }

    private static DebtEvent evento(long id, Debt deuda, DebtEvent.Type tipo, String referencia, String detalle,
                                    String cuando) {
        DebtEvent e = DebtEvent.de(deuda, tipo, tipo == DebtEvent.Type.disputed ? DebtEvent.Actor.debtor
                : DebtEvent.Actor.agency).conReferencia(referencia).conDetalle(detalle);
        e.setOccurredAt(Instant.parse(cuando));
        ReflectionTestUtils.setField(e, "id", id);
        return e;
    }

    @Test
    void los_abiertos_dicen_quien_responde_y_los_dias_de_chile_que_llevan() {
        Debt conCampana = deuda(15, true);
        Debt sinCampana = deuda(16, false);
        when(eventos.reclamosAbiertos(eq(DebtEvent.Type.disputed), eq(Debt.Status.disputed), any()))
                .thenReturn(new PageImpl<>(List.of(
                        evento(1, conCampana, DebtEvent.Type.disputed, "ya_pagada",
                                "{\"detalle\":\"<b>Pague</b> en la oficina\"}", "2026-11-10T03:30:00Z"),
                        //  El 17 a las 23:00 de Chile: lleva un dia, aunque en UTC ya sea el 18.
                        evento(2, sinCampana, DebtEvent.Type.disputed, "no_reconoce", null, "2026-11-18T02:00:00Z"))));

        List<ReclamoAdminResponse> filas = servicio.abiertos(0, 20).getContent();

        assertEquals(8, filas.get(0).dias());
        assertEquals("APOFYX", filas.get(0).cobra());
        assertEquals("Arriendos septiembre", filas.get(0).campana());
        assertEquals("<b>Pague</b> en la oficina", filas.get(0).detalle(), "tal cual: el portal lo muestra como texto");
        assertEquals("ya_pagada", filas.get(0).motivo());
        assertNull(filas.get(0).resuelto());
        assertEquals(1, filas.get(1).dias());
        assertEquals("Patrimonio Inmuebles", filas.get(1).cobra(), "sin campana, responde el acreedor");
        assertNull(filas.get(1).detalle());
    }

    @Test
    void como_mucho_50_por_pagina_y_una_pagina_negativa_es_400() {
        ArgumentCaptor<Pageable> pagina = ArgumentCaptor.forClass(Pageable.class);
        when(eventos.reclamosAbiertos(any(), any(), pagina.capture())).thenReturn(Page.empty());

        servicio.abiertos(2, 500);

        assertEquals(50, pagina.getValue().getPageSize());
        assertEquals(2, pagina.getValue().getPageNumber());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ApiException.class, () -> servicio.abiertos(-1, 20)).getStatus());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ApiException.class, () -> servicio.resueltos(null, null, 0, 0)).getStatus());
    }

    @Test
    void sin_fechas_los_resueltos_son_los_de_los_ultimos_30_dias_de_chile() {
        when(eventos.reclamosResueltos(any(), any(), any(), any(), any(), any(), any())).thenReturn(Page.empty());

        servicio.resueltos(null, null, 0, 20);

        verify(eventos).reclamosResueltos(eq(DebtEvent.Type.disputed), eq(DebtEvent.Type.withdrawn),
                eq(DebtEvent.Type.updated), eq("disputa_rechazada"),
                eq(Instant.parse("2026-10-20T03:00:00Z")), eq(Instant.parse("2026-11-19T03:00:00Z")), any());
    }

    @Test
    void desde_despues_de_hasta_es_400() {
        ApiException error = assertThrows(ApiException.class,
                () -> servicio.resueltos(LocalDate.of(2026, 11, 18), LocalDate.of(2026, 11, 1), 0, 20));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verify(eventos, never()).reclamosResueltos(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void cada_cierre_va_con_su_reclamo_cuanto_tardo_y_como_se_cerro() {
        //  La misma deuda reclamada dos veces: reanudada la primera, retirada la segunda.
        Debt deuda = deuda(15, true);
        DebtEvent primero = evento(1, deuda, DebtEvent.Type.disputed, "monto_incorrecto", null, "2026-11-01T15:00:00Z");
        DebtEvent reanudada = evento(2, deuda, DebtEvent.Type.updated, "disputa_rechazada",
                "{\"nota\":\"El monto esta bien\",\"resultado\":\"reanudada\"}", "2026-11-03T15:00:00Z");
        DebtEvent segundo = evento(3, deuda, DebtEvent.Type.disputed, "ya_pagada", "{\"detalle\":\"Pague\"}",
                "2026-11-10T15:00:00Z");
        DebtEvent retirada = evento(4, deuda, DebtEvent.Type.withdrawn, "disputa_resuelta", null,
                "2026-11-15T15:00:00Z");
        when(eventos.reclamosResueltos(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(retirada, reanudada)));
        when(eventos.findByDebtInAndType(List.of(deuda), DebtEvent.Type.disputed)).thenReturn(List.of(primero, segundo));

        List<ReclamoAdminResponse> filas =
                servicio.resueltos(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 18), 0, 20).getContent();

        assertEquals("ya_pagada", filas.get(0).motivo());
        assertEquals("Pague", filas.get(0).detalle());
        assertEquals("retirada", filas.get(0).resultado());
        assertEquals("disputa_resuelta", filas.get(0).motivoRetiro());
        assertEquals(5, filas.get(0).dias());
        assertNull(filas.get(0).nota());
        assertEquals(retirada.getOccurredAt(), filas.get(0).resuelto());

        assertEquals("monto_incorrecto", filas.get(1).motivo());
        assertEquals("reanudada", filas.get(1).resultado());
        assertNull(filas.get(1).motivoRetiro());
        assertEquals("El monto esta bien", filas.get(1).nota());
        assertEquals(2, filas.get(1).dias());
    }
}
