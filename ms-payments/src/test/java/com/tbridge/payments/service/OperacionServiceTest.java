package com.tbridge.payments.service;

import com.tbridge.payments.dto.response.OperacionResponse;
import com.tbridge.payments.dto.response.UfResponse;
import com.tbridge.payments.exception.ApiException;
import com.tbridge.payments.model.AdminLog;
import com.tbridge.payments.model.DebtNotification;
import com.tbridge.payments.model.Payment;
import com.tbridge.payments.model.PaymentEvent;
import com.tbridge.payments.model.UfValue;
import com.tbridge.payments.repository.AdminLogRepository;
import com.tbridge.payments.repository.DebtNotificationRepository;
import com.tbridge.payments.repository.PaymentEventRepository;
import com.tbridge.payments.repository.PaymentRepository;
import com.tbridge.payments.repository.UfValueRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La operacion de los pagos en el panel (#139): los totales, la UF de hoy en
 * Chile y los avisos a ms-debt; reintentar un aviso y cargar la UF quedan
 * anotados con quien lo hizo.
 */
class OperacionServiceTest {

    //  27 de octubre de 2026, 00:30 en Chile: en UTC ya es el 27 a las 03:30.
    private static final Instant AHORA = Instant.parse("2026-10-27T03:30:00Z");
    private static final LocalDate HOY = LocalDate.parse("2026-10-27");

    private PaymentRepository pagos;
    private PaymentEventRepository eventos;
    private DebtNotificationRepository avisos;
    private UfValueRepository ufs;
    private UfService uf;
    private AdminLogRepository bitacora;
    private OperacionService operacion;

    @BeforeEach
    void preparar() {
        pagos = mock(PaymentRepository.class);
        eventos = mock(PaymentEventRepository.class);
        avisos = mock(DebtNotificationRepository.class);
        ufs = mock(UfValueRepository.class);
        uf = mock(UfService.class);
        bitacora = mock(AdminLogRepository.class);
        operacion = new OperacionService(pagos, eventos, avisos, ufs, uf, bitacora, Clock.fixed(AHORA, ZoneOffset.UTC));
    }

    private static DebtNotification aviso(long pago, int fallos) {
        DebtNotification aviso = DebtNotification.para(pago);
        for (int i = 0; i < fallos; i++) {
            aviso.fallo("Connection refused");
        }
        return aviso;
    }

    @Test
    void los_totales_de_los_pagos() {
        when(pagos.countByStatus(Payment.Status.created)).thenReturn(3L);
        when(pagos.countByStatusAndCreatedAtBefore(Payment.Status.created, AHORA.minusSeconds(3600))).thenReturn(1L);
        when(eventos.countByTypeAndOccurredAtAfter(PaymentEvent.Type.expired, AHORA.minusSeconds(86_400))).thenReturn(4L);
        when(pagos.countByStatus(Payment.Status.duplicated)).thenReturn(1L);
        when(ufs.findById(any())).thenReturn(Optional.empty());

        OperacionResponse.Pagos totales = operacion.operacion().pagos();

        assertEquals(3, totales.abiertos());
        assertEquals(1, totales.abiertosHaceMasDeUnaHora());
        assertEquals(4, totales.vencidosUltimas24Horas());
        assertEquals(1, totales.duplicados());
    }

    @Test
    void la_uf_de_hoy_es_la_del_dia_en_chile() {
        when(ufs.findById(HOY)).thenReturn(Optional.of(new UfValue(HOY, new BigDecimal("40123.45"), "bcentral")));

        OperacionResponse.Uf deHoy = operacion.operacion().uf();

        assertEquals(HOY, deHoy.dia());
        assertTrue(deHoy.cargada());
        assertEquals(new BigDecimal("40123.45"), deHoy.valor());
        assertEquals("bcentral", deHoy.fuente());
    }

    @Test
    void un_dia_sin_uf_lo_dice() {
        when(ufs.findById(HOY)).thenReturn(Optional.empty());

        OperacionResponse.Uf deHoy = operacion.operacion().uf();

        assertFalse(deHoy.cargada());
        assertNull(deHoy.valor());
    }

    @Test
    void los_avisos_a_ms_debt_sin_entregar_con_su_pago_y_sin_datos_del_deudor() {
        when(ufs.findById(any())).thenReturn(Optional.empty());
        when(avisos.countByStatus(DebtNotification.Status.failed)).thenReturn(1L);
        when(avisos.findTop200ByStatusInOrderByIdDesc(any())).thenReturn(List.of(aviso(58, 6), aviso(59, 2)));

        OperacionResponse.Avisos sinEntregar = operacion.operacion().avisos();

        assertEquals(1, sinEntregar.fallidos());
        assertEquals(58L, sinEntregar.lista().getFirst().pago());
        assertEquals("failed", sinEntregar.lista().getFirst().estado());
        assertEquals(6, sinEntregar.lista().getFirst().intentos());
        assertNull(sinEntregar.lista().getFirst().proximoIntento());
        assertEquals("pending", sinEntregar.lista().get(1).estado());
        assertEquals(6, OperacionResponse.Aviso.class.getRecordComponents().length, "solo el pago, sin el deudor");
    }

    @Test
    void lo_ultimo_que_se_hizo_desde_el_panel_dice_quien() {
        when(ufs.findById(any())).thenReturn(Optional.empty());
        when(bitacora.findTop20ByOrderByOccurredAtDesc()).thenReturn(List.of(
                AdminLog.de("admin@databridge.cl", AdminLog.Action.uf_cargada, "2026-10-27: 40123.45")));

        OperacionResponse.Accion ultima = operacion.operacion().acciones().getFirst();

        assertEquals("uf_cargada", ultima.accion());
        assertEquals("2026-10-27: 40123.45", ultima.sobre());
        assertEquals("admin@databridge.cl", ultima.quien());
    }

    @Test
    void reintentar_un_aviso_lo_deja_en_cola_y_lo_anota() {
        DebtNotification fallido = aviso(58, 6);
        when(avisos.findById(3L)).thenReturn(Optional.of(fallido));

        OperacionResponse.Aviso enCola = operacion.reintentarAviso(3L, "admin@databridge.cl");

        assertEquals(DebtNotification.Status.pending, fallido.getStatus());
        assertEquals("pending", enCola.estado());
        verify(avisos).save(fallido);
        ArgumentCaptor<AdminLog> registro = ArgumentCaptor.forClass(AdminLog.class);
        verify(bitacora).save(registro.capture());
        assertEquals("admin@databridge.cl", registro.getValue().getAdminEmail());
        assertEquals(AdminLog.Action.aviso_reintentado, registro.getValue().getAction());
        assertEquals("el aviso del pago 58", registro.getValue().getSubject());
    }

    @Test
    void un_aviso_entregado_o_que_no_existe_no_se_reintenta() {
        DebtNotification entregado = aviso(58, 0);
        entregado.entregado();
        when(avisos.findById(1L)).thenReturn(Optional.of(entregado));
        when(avisos.findById(2L)).thenReturn(Optional.empty());

        assertEquals(HttpStatus.CONFLICT,
                assertThrows(ApiException.class, () -> operacion.reintentarAviso(1L, "admin@databridge.cl")).getStatus());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ApiException.class, () -> operacion.reintentarAviso(2L, "admin@databridge.cl")).getStatus());
        verify(bitacora, never()).save(any());
    }

    @Test
    void cargar_la_uf_usa_la_carga_de_siempre_y_anota_quien_la_cargo() {
        when(uf.cargarAMano(HOY, new BigDecimal("40123.45")))
                .thenReturn(new UfResponse(HOY, new BigDecimal("40123.45"), "manual"));

        UfResponse cargada = operacion.cargarUf(HOY, new BigDecimal("40123.45"), "admin@databridge.cl");

        assertEquals("manual", cargada.fuente());
        ArgumentCaptor<AdminLog> registro = ArgumentCaptor.forClass(AdminLog.class);
        verify(bitacora).save(registro.capture());
        assertEquals(AdminLog.Action.uf_cargada, registro.getValue().getAction());
        assertEquals("2026-10-27: 40123.45", registro.getValue().getSubject());
    }

    @Test
    void si_la_uf_no_se_puede_cargar_no_se_anota_nada() {
        when(uf.cargarAMano(any(), any())).thenThrow(new ApiException(HttpStatus.BAD_REQUEST, "La UF tiene que ser positiva"));

        assertThrows(ApiException.class, () -> operacion.cargarUf(HOY, BigDecimal.ZERO, "admin@databridge.cl"));
        verify(bitacora, never()).save(any());
    }
}
