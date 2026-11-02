package com.tbridge.auth.service;

import com.tbridge.auth.dto.response.AccesoResponse;
import com.tbridge.auth.dto.response.AccesosDeHoyResponse;
import com.tbridge.auth.exception.ApiException;
import com.tbridge.auth.model.AccessLog;
import com.tbridge.auth.repository.AccessLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * La bitacora de acceso del panel (#141): los dias son de Chile, las paginas
 * tienen tope y lo que sale no identifica a la persona.
 */
class BitacoraDeAccesosTest {

    //  27 de octubre de 2026, 12:00 en Chile (UTC-3 en horario de verano).
    private static final Instant AHORA = Instant.parse("2026-10-27T15:00:00Z");

    private AccessLogRepository repositorio;
    private BitacoraDeAccesos bitacora;

    @BeforeEach
    void preparar() {
        repositorio = mock(AccessLogRepository.class);
        when(repositorio.buscar(any(), any(), any(), any(), any())).thenReturn(Page.empty());
        bitacora = new BitacoraDeAccesos(repositorio, Clock.fixed(AHORA, ZoneOffset.UTC));
    }

    private Pageable paginaPedida() {
        ArgumentCaptor<Pageable> pagina = ArgumentCaptor.forClass(Pageable.class);
        verify(repositorio).buscar(any(), any(), any(), any(), pagina.capture());
        return pagina.getValue();
    }

    @Test
    void los_dias_son_de_chile_y_quedan_incluidos() {
        bitacora.buscar(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-27"), null, null, 0, 0);

        //  Desde la medianoche del 1 en Chile hasta la del 28: el 27 entero queda dentro.
        verify(repositorio).buscar(eq(Instant.parse("2026-10-01T03:00:00Z")), eq(Instant.parse("2026-10-28T03:00:00Z")),
                eq(null), eq(null), any());
    }

    @Test
    void sin_fechas_es_desde_el_principio_hasta_hoy() {
        bitacora.buscar(null, null, AccessLog.Method.code, AccessLog.Outcome.invalid, 0, 0);

        verify(repositorio).buscar(eq(Instant.EPOCH), eq(AHORA.plusSeconds(86_400)),
                eq(AccessLog.Method.code), eq(AccessLog.Outcome.invalid), any());
    }

    @Test
    void lo_mas_reciente_primero_y_de_a_50_si_no_se_dice() {
        bitacora.buscar(null, null, null, null, 3, 0);

        Pageable pagina = paginaPedida();
        assertEquals(3, pagina.getPageNumber());
        assertEquals(BitacoraDeAccesos.POR_PAGINA, pagina.getPageSize());
        assertEquals(Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id")), pagina.getSort());
    }

    @Test
    void nunca_mas_de_100_por_pagina() {
        bitacora.buscar(null, null, null, null, 0, 5000);

        assertEquals(BitacoraDeAccesos.MAXIMO_POR_PAGINA, paginaPedida().getPageSize());
    }

    @Test
    void una_pagina_negativa_o_hasta_antes_de_desde_responden_400_sin_consultar() {
        ApiException pagina = assertThrows(ApiException.class,
                () -> bitacora.buscar(null, null, null, null, -1, 0));
        ApiException fechas = assertThrows(ApiException.class,
                () -> bitacora.buscar(LocalDate.parse("2026-10-27"), LocalDate.parse("2026-10-01"), null, null, 0, 0));

        assertEquals(HttpStatus.BAD_REQUEST, pagina.getStatus());
        assertEquals(HttpStatus.BAD_REQUEST, fechas.getStatus());
        verifyNoInteractions(repositorio);
    }

    @Test
    void cada_fila_sale_con_el_rut_a_medias_y_sin_la_huella_de_la_ip() {
        AccessLog intento = AccessLog.de("18905214-6", AccessLog.Method.code, AccessLog.Outcome.invalid,
                "a".repeat(64));
        when(repositorio.buscar(any(), any(), any(), any(), any())).thenReturn(new PageImpl<>(List.of(intento)));

        AccesoResponse fila = bitacora.buscar(null, null, null, null, 0, 0).getContent().getFirst();

        assertEquals("18.***.***-6", fila.rut());
        assertEquals("code", fila.metodo());
        assertEquals("invalid", fila.resultado());
        //  La respuesta no tiene donde llevar la IP ni un correo.
        assertEquals(4, AccesoResponse.class.getRecordComponents().length);
    }

    @Test
    void el_rut_a_medias() {
        assertEquals("18.***.***-6", AccesoResponse.from(AccessLog.de("18905214-6", AccessLog.Method.code,
                AccessLog.Outcome.granted, null)).rut());
        assertEquals("1.***.***-K", AccesoResponse.from(AccessLog.de("1234567-K", AccessLog.Method.code,
                AccessLog.Outcome.granted, null)).rut());
        //  El personal de una empresa entra sin RUT: no hay nada que mostrar.
        assertNull(AccesoResponse.from(AccessLog.de(null, AccessLog.Method.magic_link,
                AccessLog.Outcome.granted, null)).rut());
    }

    private static AccessLogRepository.PorResultado fila(AccessLog.Outcome resultado, long cantidad) {
        return new AccessLogRepository.PorResultado() {
            @Override public AccessLog.Outcome getResultado() { return resultado; }
            @Override public long getCantidad() { return cantidad; }
        };
    }

    @Test
    void los_totales_de_hoy_cuentan_desde_la_medianoche_de_chile() {
        when(repositorio.contarPorResultadoDesde(Instant.parse("2026-10-27T03:00:00Z"))).thenReturn(List.of(
                fila(AccessLog.Outcome.granted, 5), fila(AccessLog.Outcome.invalid, 3),
                fila(AccessLog.Outcome.expired, 1), fila(AccessLog.Outcome.refused, 1),
                fila(AccessLog.Outcome.sent, 7)));

        AccesosDeHoyResponse hoy = bitacora.hoy();

        assertEquals(LocalDate.parse("2026-10-27"), hoy.dia());
        assertEquals(5, hoy.entraron());
        assertEquals(5, hoy.rechazados(), "invalid, expired y refused son rechazos");
        assertEquals(7, hoy.enviados());
        assertEquals(6, hoy.porResultado().size(), "trae todos los resultados, tambien los que no hubo");
        assertEquals(0L, hoy.porResultado().get("exhausted"));
    }
}
