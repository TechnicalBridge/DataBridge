package com.tbridge.payments.service;

import com.tbridge.payments.client.DebtClient;
import com.tbridge.payments.client.KhipuClient;
import com.tbridge.payments.client.MercadoPagoClient;
import com.tbridge.payments.client.WebpayClient;
import com.tbridge.payments.dto.request.CheckoutRequest;
import com.tbridge.payments.exception.ApiException;
import com.tbridge.payments.model.Payment;
import com.tbridge.payments.repository.DebtNotificationRepository;
import com.tbridge.payments.repository.PaymentEventRepository;
import com.tbridge.payments.repository.PaymentRepository;
import com.tbridge.payments.security.JwtPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MercadoPagoConfirmacionTest {
    private final PaymentRepository pagos = mock(PaymentRepository.class);
    private final PaymentEventRepository eventos = mock(PaymentEventRepository.class);
    private final DebtNotificationRepository avisos = mock(DebtNotificationRepository.class);
    private final MercadoPagoClient mercadoPago = mock(MercadoPagoClient.class);
    private final DebtClient deudas = mock(DebtClient.class);
    private final WebhookVerifier firmas = new WebhookVerifier("secreto-de-prueba");
    private PaymentService servicio;
    private Payment pago;

    @BeforeEach
    void preparar() {
        servicio = new PaymentService(pagos, eventos, avisos, firmas, deudas, mock(UfService.class),
                mock(KhipuClient.class), new FirmaDeKhipu("secreto-de-prueba"), mock(WebpayClient.class),
                mercadoPago, "http://localhost:8080", "", Duration.ofMinutes(30), Duration.ofMinutes(30));
        pago = new Payment();
        pago.setId(41L);
        pago.setDebtId(3L);
        pago.setInstallmentId(12L);
        pago.setAmount(new BigDecimal("410000"));
        pago.setAmountClp(410000L);
        pago.setCurrency(Payment.Currency.CLP);
        pago.setGateway(Payment.Gateway.mercadopago);
        pago.setGatewayTxnId("pref-propia");
        pago.setStatus(Payment.Status.created);
        pago.setCreatedAt(Instant.now());
        when(pagos.findById(41L)).thenReturn(Optional.of(pago));
        when(pagos.save(any())).thenAnswer(llamada -> llamada.getArgument(0));
        when(mercadoPago.real()).thenReturn(true);
        when(mercadoPago.consultarPreferencia("pref-propia")).thenReturn(preferencia("pref-propia", 999L));
        when(mercadoPago.consultarPago("999")).thenReturn(info("410000", "CLP", "41", 999L, "approved"));
    }

    @Test
    void un_retorno_sin_firma_vuelve_al_portal_sin_consultar_ni_revelar_el_enlace() {
        assertEquals("http://localhost:8080/app", servicio.retornoMercadoPago(41L, null));
        assertEquals("http://localhost:8080/app", servicio.retornoMercadoPago(null, "algo"));
        assertEquals("http://localhost:8080/app", servicio.retornoMercadoPago(null, null));
        assertEquals(Payment.Status.created, pago.getStatus());
        verifyNoInteractions(pagos, eventos, avisos);
        verify(mercadoPago, never()).consultarPreferencia(any());
    }

    @Test
    void una_firma_ajena_no_autoriza_el_retorno() {
        ApiException error = assertThrows(ApiException.class, () -> servicio.retornoMercadoPago(41L,
                firmas.sign("42", "410000", "3")));
        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatus());
        verifyNoInteractions(eventos, avisos);
    }

    @Test
    void una_caida_de_pasarela_deja_el_cobro_abierto() {
        when(mercadoPago.consultarPreferencia("pref-propia"))
                .thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "No disponible"));

        assertTrue(servicio.retornoMercadoPago(41L, firma()).contains("/pasarela/41?sig="));
        assertEquals(Payment.Status.created, pago.getStatus());
        verifyNoInteractions(eventos, avisos);
    }

    @Test
    void una_caida_al_verificar_el_pago_tampoco_confirma() {
        when(mercadoPago.consultarPago("999"))
                .thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "No disponible"));

        servicio.retornoMercadoPago(41L, firma());

        assertEquals(Payment.Status.created, pago.getStatus());
        verifyNoInteractions(eventos, avisos);
    }

    @ParameterizedTest
    @CsvSource({"1,CLP,41,999", "410000,USD,41,999", "410000,CLP,98,999", "410000,CLP,41,998"})
    void el_retorno_rechaza_monto_moneda_referencia_o_id_ajenos(String monto, String moneda, String referencia,
                                                              Long id) {
        when(mercadoPago.consultarPago("999")).thenReturn(info(monto, moneda, referencia, id, "approved"));

        servicio.retornoMercadoPago(41L, firma());

        assertEquals(Payment.Status.created, pago.getStatus());
        verifyNoInteractions(eventos, avisos);
    }

    @ParameterizedTest
    @CsvSource({"1,CLP,41,999", "410000,USD,41,999", "410000,CLP,98,999", "410000,CLP,41,998"})
    void el_aviso_rechaza_monto_moneda_referencia_o_id_ajenos(String monto, String moneda, String referencia,
                                                            Long id) {
        when(mercadoPago.consultarPago("999")).thenReturn(info(monto, moneda, referencia, id, "approved"));

        servicio.avisoMercadoPago("payment", "999", null);

        assertEquals(Payment.Status.created, pago.getStatus());
        verifyNoInteractions(eventos, avisos);
    }

    @Test
    void el_retorno_no_cierra_un_cobro_de_otra_pasarela() {
        pago.setGateway(Payment.Gateway.webpay);

        assertThrows(ApiException.class, () -> servicio.retornoMercadoPago(41L, firma()));

        assertEquals(Payment.Status.created, pago.getStatus());
        verifyNoInteractions(eventos, avisos);
    }

    @Test
    void el_aviso_no_cierra_un_cobro_de_otra_pasarela() {
        pago.setGateway(Payment.Gateway.khipu);

        servicio.avisoMercadoPago("payment", "999", null);

        assertEquals(Payment.Status.created, pago.getStatus());
        verifyNoInteractions(eventos, avisos);
    }

    @Test
    void el_aviso_no_paga_cuando_mercado_pago_es_simulado() {
        when(mercadoPago.real()).thenReturn(false);

        servicio.avisoMercadoPago("payment", "999", null);

        assertEquals(Payment.Status.created, pago.getStatus());
        verifyNoInteractions(pagos, eventos, avisos);
    }

    @Test
    void el_aviso_de_otro_intento_solo_concilia_la_preferencia_propia() {
        when(mercadoPago.consultarPreferencia("pref-propia")).thenReturn(preferencia("pref-propia", null));

        servicio.avisoMercadoPago("payment", "999", null);

        assertEquals(Payment.Status.created, pago.getStatus());
        verifyNoInteractions(eventos, avisos);
    }

    @Test
    void una_respuesta_de_otra_preferencia_no_abona() {
        when(mercadoPago.consultarPreferencia("pref-propia")).thenReturn(preferencia("pref-ajena", 999L));

        servicio.retornoMercadoPago(41L, firma());

        assertEquals(Payment.Status.created, pago.getStatus());
        verifyNoInteractions(eventos, avisos);
    }

    @Test
    void sin_identificador_de_pago_verificado_no_se_abona() {
        when(mercadoPago.consultarPago("999")).thenReturn(info("410000", "CLP", "41", null, "approved"));

        servicio.retornoMercadoPago(41L, firma());
        servicio.avisoMercadoPago("payment", "999", null);

        assertEquals(Payment.Status.created, pago.getStatus());
        verifyNoInteractions(eventos, avisos);
    }

    @ParameterizedTest
    @CsvSource({"rejected", "cancelled", "pending", "in_process"})
    void un_intento_no_aprobado_no_cierra_el_cobro(String estado) {
        when(mercadoPago.consultarPago("999")).thenReturn(info("410000", "CLP", "41", 999L, estado));

        servicio.retornoMercadoPago(41L, firma());
        servicio.avisoMercadoPago("payment", "999", null);

        assertEquals(Payment.Status.created, pago.getStatus());
        verifyNoInteractions(eventos, avisos);
    }

    @Test
    void el_aviso_verificado_coincidente_abona_una_sola_vez() {
        servicio.avisoMercadoPago("payment", null, "{\"data\":{\"id\":\"999\"}}");
        servicio.avisoMercadoPago("payment", "999", null);

        assertEquals(Payment.Status.paid, pago.getStatus());
        verify(avisos, times(1)).save(any());
        verify(eventos, times(1)).save(any());
    }

    @Test
    void un_vencido_aprobado_despues_tambien_se_concilia_por_el_aviso() {
        pago.setStatus(Payment.Status.expired);

        servicio.avisoMercadoPago("payment", "999", null);

        assertEquals(Payment.Status.paid, pago.getStatus());
        verify(avisos).save(any());
    }

    @Test
    void el_aviso_invalido_no_consulta_la_pasarela() {
        servicio.avisoMercadoPago("payment", null, "{malformado");
        servicio.avisoMercadoPago("payment", null, "{}");
        servicio.avisoMercadoPago("payment", null, "");
        servicio.avisoMercadoPago("payment", "identificador-invalido", null);

        verify(mercadoPago, never()).consultarPago(any());
        verifyNoInteractions(eventos, avisos);
    }

    @Test
    void el_aviso_reintentara_si_la_pasarela_no_responde() {
        when(mercadoPago.consultarPago("999"))
                .thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "No disponible"));

        assertThrows(ApiException.class, () -> servicio.avisoMercadoPago("payment", "999", null));

        assertEquals(Payment.Status.created, pago.getStatus());
        verifyNoInteractions(eventos, avisos);
    }

    @Test
    void el_checkout_entrega_un_retorno_firmado_para_el_cobro() {
        when(deudas.obtener(3L, null)).thenReturn(new DebtClient.DebtSnapshot(3L, "76000000-0", "16482337-7",
                "CLP", new BigDecimal("410000"), 12L, List.of(12L), new BigDecimal("410000"), BigDecimal.ZERO));
        when(pagos.save(any())).thenAnswer(llamada -> {
            Payment nuevo = llamada.getArgument(0);
            nuevo.setId(41L);
            return nuevo;
        });
        when(mercadoPago.crearPreferencia(any(), any(), eq(410000L), any(), any(), any()))
                .thenReturn(new MercadoPagoClient.Preferencia("pref-propia", "https://pasarela.example", null));

        servicio.checkout(new JwtPrincipal("16482337-7", null, "DEBTOR", null, "16482337-7"),
                new CheckoutRequest(3L, null, "mercadopago"));

        verify(mercadoPago).crearPreferencia(eq("41"), any(), eq(410000L), any(),
                eq("http://localhost:8080/api/payments/public/mercadopago/retorno?pago=41&sig=" + firma()), any());
    }

    private String firma() {
        return firmas.sign("41", "410000", "3");
    }

    private MercadoPagoClient.EstadoPreferencia preferencia(String id, Long pagoId) {
        return new MercadoPagoClient.EstadoPreferencia(id, "41", 410000L, pagoId == null ? null
                : new MercadoPagoClient.PagoDePreferencia(pagoId, "approved", null));
    }

    private MercadoPagoClient.PagoInfo info(String monto, String moneda, String referencia, Long id, String estado) {
        return new MercadoPagoClient.PagoInfo(id, estado, "accredited", new BigDecimal(monto), moneda, referencia, null);
    }
}
