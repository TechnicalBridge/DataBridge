package com.tbridge.payments.service;

import com.tbridge.payments.dto.response.PagoAdminResponse;
import com.tbridge.payments.dto.response.PagoVerificadoResponse;
import com.tbridge.payments.exception.ApiException;
import com.tbridge.payments.model.AdminLog;
import com.tbridge.payments.model.Payment;
import com.tbridge.payments.model.PaymentEvent;
import com.tbridge.payments.repository.AdminLogRepository;
import com.tbridge.payments.repository.PaymentEventRepository;
import com.tbridge.payments.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** La pantalla Pagos del panel (#435). */
class PagosAdminServiceTest {

    private static final Instant AHORA = Instant.parse("2026-11-18T15:00:00Z");

    private final PaymentRepository pagos = mock(PaymentRepository.class);
    private final PaymentEventRepository eventos = mock(PaymentEventRepository.class);
    private final AdminLogRepository bitacora = mock(AdminLogRepository.class);
    private final PaymentService cobros = mock(PaymentService.class);
    private final PagosAdminService servicio =
            new PagosAdminService(pagos, eventos, bitacora, cobros, Clock.fixed(AHORA, ZoneOffset.UTC));

    private static Payment pago(Long id, Payment.Status estado, Duration hace) {
        Payment pago = new Payment();
        pago.setId(id);
        pago.setDebtId(15L);
        pago.setDebtorRut("16482337-7");
        pago.setCreditorRut("76418902-7");
        pago.setGateway(Payment.Gateway.webpay);
        pago.setStatus(estado);
        pago.setAmount(new BigDecimal("125000"));
        pago.setCurrency(Payment.Currency.CLP);
        pago.setAmountClp(125000L);
        pago.setCuotas(List.of(60L));
        pago.setCreatedAt(AHORA.minus(hace));
        return pago;
    }

    @Test
    void los_filtros_llegan_al_repositorio_en_su_forma_y_en_dias_de_chile() {
        ArgumentCaptor<Pageable> pagina = ArgumentCaptor.forClass(Pageable.class);
        when(pagos.paraElPanel(any(), any(), any(), any(), any(), anyBoolean(), any(), pagina.capture()))
                .thenReturn(Page.empty());

        servicio.buscar("created", "webpay", "76.418.902-7", LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 18),
                true, 0, 500);

        verify(pagos).paraElPanel(eq(Payment.Status.created), eq(Payment.Gateway.webpay), eq("76418902-7"),
                eq(Instant.parse("2026-11-01T03:00:00Z")), eq(Instant.parse("2026-11-19T03:00:00Z")), eq(true),
                eq(AHORA.minus(Duration.ofHours(1))), any());
        assertEquals(50, pagina.getValue().getPageSize());
    }

    @Test
    void sin_filtros_no_se_filtra_por_nada() {
        when(pagos.paraElPanel(any(), any(), any(), any(), any(), anyBoolean(), any(), any())).thenReturn(Page.empty());

        servicio.buscar(null, " ", "", null, null, false, 0, 20);

        verify(pagos).paraElPanel(isNull(), isNull(), isNull(), isNull(), isNull(), eq(false), any(), any());
    }

    @Test
    void un_estado_una_pasarela_un_rut_o_fechas_que_no_sirven_son_400() {
        for (Runnable malo : List.<Runnable>of(
                () -> servicio.buscar("pagadito", null, null, null, null, false, 0, 20),
                () -> servicio.buscar(null, "paypal", null, null, null, false, 0, 20),
                () -> servicio.buscar(null, null, "hola", null, null, false, 0, 20),
                () -> servicio.buscar(null, null, null, LocalDate.of(2026, 11, 18), LocalDate.of(2026, 11, 1),
                        false, 0, 20),
                () -> servicio.buscar(null, null, null, null, null, false, -1, 20))) {
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(ApiException.class, malo::run).getStatus());
        }
        verify(pagos, never()).paraElPanel(any(), any(), any(), any(), any(), anyBoolean(), any(), any());
    }

    @Test
    void se_resaltan_el_abierto_hace_mas_de_una_hora_y_el_duplicado() {
        when(pagos.paraElPanel(any(), any(), any(), any(), any(), anyBoolean(), any(), any()))
                .thenReturn(new PageImpl<>(List.of(pago(1L, Payment.Status.created, Duration.ofHours(2)),
                        pago(2L, Payment.Status.created, Duration.ofMinutes(10)),
                        pago(3L, Payment.Status.duplicated, Duration.ofDays(1)),
                        pago(4L, Payment.Status.paid, Duration.ofDays(1)))));

        List<PagoAdminResponse> filas = servicio.buscar(null, null, null, null, null, false, 0, 20).getContent();

        assertTrue(filas.get(0).abiertoHaceMasDeUnaHora());
        assertFalse(filas.get(1).abiertoHaceMasDeUnaHora());
        assertTrue(filas.get(2).duplicado());
        assertFalse(filas.get(3).abiertoHaceMasDeUnaHora() || filas.get(3).duplicado());
        assertEquals(1, filas.get(0).cuotas());
    }

    @Test
    void el_detalle_trae_la_historia_en_orden_y_un_pago_que_no_existe_es_404() {
        when(pagos.findById(7L)).thenReturn(Optional.of(pago(7L, Payment.Status.paid, Duration.ofDays(1))));
        when(eventos.findByPaymentIdOrderByIdAsc(7L)).thenReturn(List.of(
                PaymentEvent.de(7L, PaymentEvent.Type.created, PaymentEvent.Source.portal),
                PaymentEvent.de(7L, PaymentEvent.Type.paid, PaymentEvent.Source.webhook)));

        var detalle = servicio.detalle(7L);

        assertEquals(7L, detalle.pago().id());
        assertEquals(List.of(PaymentEvent.Type.created, PaymentEvent.Type.paid),
                detalle.historia().stream().map(e -> e.tipo()).toList());
        when(pagos.findById(8L)).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class, () -> servicio.detalle(8L)).getStatus());
    }

    @Test
    void verificar_queda_en_la_bitacora_con_como_quedo_el_pago() {
        when(cobros.verificarComoAdmin(9L)).thenReturn(pago(9L, Payment.Status.paid, Duration.ofHours(3)));

        PagoVerificadoResponse respuesta = servicio.verificar(9L, "admin@databridge.cl");

        assertEquals(Payment.Status.created, respuesta.antes());
        assertEquals(Payment.Status.paid, respuesta.ahora());
        ArgumentCaptor<AdminLog> registro = ArgumentCaptor.forClass(AdminLog.class);
        verify(bitacora).save(registro.capture());
        assertEquals(AdminLog.Action.pago_verificado, registro.getValue().getAction());
        assertEquals("el pago 9 de webpay, que quedó pagado", registro.getValue().getSubject());
        assertEquals("admin@databridge.cl", registro.getValue().getAdminEmail());
    }

    @Test
    void si_la_verificacion_falla_no_queda_nada_en_la_bitacora() {
        when(cobros.verificarComoAdmin(9L)).thenThrow(new ApiException(HttpStatus.CONFLICT, "ya no está abierto"));

        assertThrows(ApiException.class, () -> servicio.verificar(9L, "admin@databridge.cl"));

        verify(bitacora, never()).save(any());
    }
}
