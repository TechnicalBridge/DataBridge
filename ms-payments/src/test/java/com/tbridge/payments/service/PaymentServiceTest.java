package com.tbridge.payments.service;

import com.tbridge.payments.exception.ApiException;
import com.tbridge.payments.security.JwtPrincipal;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.payments.client.DebtClient;
import com.tbridge.payments.client.KhipuClient;
import com.tbridge.payments.client.MercadoPagoClient;
import com.tbridge.payments.client.WebpayClient;
import com.tbridge.payments.dto.gateway.WebpayCommitResponse;
import com.tbridge.payments.dto.gateway.WebpayCreateResponse;
import com.tbridge.payments.dto.request.CheckoutRequest;
import com.tbridge.payments.dto.request.WebhookRequest;
import com.tbridge.payments.dto.response.PaymentResponse;
import com.tbridge.payments.model.DebtNotification;
import com.tbridge.payments.model.Payment;
import com.tbridge.payments.model.PaymentEvent;
import com.tbridge.payments.model.UfValue;
import com.tbridge.payments.repository.DebtNotificationRepository;
import com.tbridge.payments.repository.PaymentEventRepository;
import com.tbridge.payments.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Las reglas del cobro: el monto sale de ms-debt, solo se paga lo propio, y
 * confirmar dos veces no cobra dos veces.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class PaymentServiceTest {

    private static final String FELIPE = "16482337-7";
    private static final JwtPrincipal DEUDOR = new JwtPrincipal(FELIPE, null, "DEBTOR", null, FELIPE);

    @Mock private PaymentRepository payments;
    @Mock private PaymentEventRepository eventos;
    @Mock private DebtNotificationRepository avisos;
    @Mock private DebtClient deudas;
    @Mock private UfService uf;
    @Mock private KhipuClient khipu;
    @Mock private WebpayClient webpay;
    @Mock private com.tbridge.payments.client.MercadoPagoClient mercadopago;

    private final WebhookVerifier firmas = new WebhookVerifier("secreto-de-prueba");
    private final FirmaDeKhipu firmaDeKhipu = new FirmaDeKhipu("secreto-de-khipu");
    private PaymentService servicio;

    @BeforeEach
    void preparar() {
        servicio = new PaymentService(payments, eventos, avisos, firmas, deudas, uf, khipu, firmaDeKhipu, webpay,
                mercadopago, "http://localhost:8080/", "", java.time.Duration.ofMinutes(30),
                java.time.Duration.ofMinutes(30));
        when(payments.save(any())).thenAnswer(llamada -> {
            Payment pago = llamada.getArgument(0);
            if (pago.getId() == null) {
                pago.setId(41L);
            }
            return pago;
        });
        when(avisos.findByPaymentId(any())).thenReturn(Optional.empty());
        when(webpay.plazoDePago()).thenReturn(java.time.Duration.ofMinutes(15));
    }

    private static DebtClient.DebtSnapshot deudaDe(String rut, String moneda, String monto) {
        return new DebtClient.DebtSnapshot(3L, "76418902-7", rut, moneda, new BigDecimal(monto), 12L, List.of(12L),
                new BigDecimal(monto), BigDecimal.ZERO);
    }

    @Test
    void el_monto_lo_pone_ms_debt_y_no_la_peticion() {
        when(deudas.obtener(3L, null)).thenReturn(deudaDe(FELIPE, "CLP", "410000"));

        PaymentResponse pago = servicio.checkout(DEUDOR, new CheckoutRequest(3L, null, "webpay"));

        assertEquals(new BigDecimal("410000"), pago.amount());
        assertEquals(410000L, pago.amountClp());
        assertEquals(Payment.Status.created, pago.status());
        assertTrue(pago.checkoutUrl().startsWith("http://localhost:8080/pasarela/41?sig="));
    }

    @Test
    void la_mora_que_informa_ms_debt_queda_fija_en_el_cobro() {
        when(deudas.obtener(3L, null)).thenReturn(new DebtClient.DebtSnapshot(3L, "76418902-7", FELIPE, "CLP",
                new BigDecimal("416150"), 12L, List.of(12L), new BigDecimal("410000"), new BigDecimal("6150")));
        ArgumentCaptor<Payment> guardado = ArgumentCaptor.forClass(Payment.class);

        PaymentResponse pago = servicio.checkout(DEUDOR, new CheckoutRequest(3L, null, "webpay"));

        assertEquals(new BigDecimal("416150"), pago.amount(), "se cobra el capital mas la mora");
        verify(payments, org.mockito.Mockito.atLeastOnce()).save(guardado.capture());
        assertEquals(new BigDecimal("6150"), guardado.getValue().getInterestAmount());
    }

    @Test
    void el_descuento_por_pronto_pago_tambien_queda_fijo_en_el_cobro() {
        //  El ejemplo del contrato: $900.000 + $18.800 de mora - $9.400 de descuento.
        when(deudas.obtener(3L, null)).thenReturn(new DebtClient.DebtSnapshot(3L, "76418902-7", FELIPE, "CLP",
                new BigDecimal("909400"), 12L, List.of(12L), new BigDecimal("900000"), new BigDecimal("9400"),
                new BigDecimal("9400")));
        ArgumentCaptor<Payment> guardado = ArgumentCaptor.forClass(Payment.class);

        PaymentResponse pago = servicio.checkout(DEUDOR, new CheckoutRequest(3L, null, "webpay"));

        assertEquals(new BigDecimal("909400"), pago.amount(), "se cobra lo que dice ms-debt, ya con el descuento");
        verify(payments, org.mockito.Mockito.atLeastOnce()).save(guardado.capture());
        assertEquals(new BigDecimal("9400"), guardado.getValue().getInterestAmount());
        assertEquals(new BigDecimal("9400"), guardado.getValue().getDiscountAmount());
    }

    @Test
    void sin_descuento_el_cobro_no_lo_guarda() {
        when(deudas.obtener(3L, null)).thenReturn(new DebtClient.DebtSnapshot(3L, "76418902-7", FELIPE, "CLP",
                new BigDecimal("416150"), 12L, List.of(12L), new BigDecimal("410000"), new BigDecimal("6150"),
                BigDecimal.ZERO));
        ArgumentCaptor<Payment> guardado = ArgumentCaptor.forClass(Payment.class);

        servicio.checkout(DEUDOR, new CheckoutRequest(3L, null, "webpay"));

        verify(payments, org.mockito.Mockito.atLeastOnce()).save(guardado.capture());
        org.junit.jupiter.api.Assertions.assertNull(guardado.getValue().getDiscountAmount());
    }

    @Test
    void las_cuotas_elegidas_viajan_a_ms_debt_que_es_quien_pone_el_monto() {
        when(deudas.obtener(3L, List.of(12L, 13L))).thenReturn(deudaDe(FELIPE, "CLP", "280000"));

        PaymentResponse pago = servicio.checkout(DEUDOR, new CheckoutRequest(3L, List.of(12L, 13L), "webpay"));

        assertEquals(new BigDecimal("280000"), pago.amount());
    }

    @Test
    void en_uf_los_pesos_se_fijan_al_abrir_con_la_uf_del_dia() {
        when(deudas.obtener(3L, null)).thenReturn(deudaDe(FELIPE, "UF", "38.50"));
        when(uf.delDia(any())).thenReturn(new UfValue(null, new BigDecimal("39876.54"), "manual"));
        when(uf.aPesos(new BigDecimal("38.50"), new BigDecimal("39876.54"))).thenReturn(1535247L);

        PaymentResponse pago = servicio.checkout(DEUDOR, new CheckoutRequest(3L, null, "khipu"));

        assertEquals(new BigDecimal("39876.54"), pago.ufValue());
        assertEquals(1535247L, pago.amountClp());
    }

    @Test
    void nadie_paga_la_deuda_de_otro() {
        when(deudas.obtener(3L, null)).thenReturn(deudaDe("18905214-6", "CLP", "410000"));

        ApiException error = assertThrows(ApiException.class,
                () -> servicio.checkout(DEUDOR, new CheckoutRequest(3L, null, "webpay")));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        verify(payments, never()).save(any());
    }

    @Test
    void una_empresa_no_paga_deudas() {
        JwtPrincipal empresa = new JwtPrincipal("1", "camila.reyes@apofyx.cl", "CREDITOR", "Camila", "77305118-6");

        ApiException error = assertThrows(ApiException.class,
                () -> servicio.checkout(empresa, new CheckoutRequest(3L, null, "webpay")));

        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        verify(deudas, never()).obtener(any(), any());
    }

    @Test
    void una_pasarela_que_no_existe_es_400() {
        ApiException error = assertThrows(ApiException.class,
                () -> servicio.checkout(DEUDOR, new CheckoutRequest(3L, null, "paypal")));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
    }

    private Payment pagoAbierto() {
        Payment pago = new Payment();
        pago.setId(41L);
        pago.setDebtId(3L);
        pago.setDebtorRut(FELIPE);
        pago.setCreditorRut("76418902-7");
        pago.setAmount(new BigDecimal("410000.00"));
        pago.setAmountClp(410000L);
        pago.setCurrency(Payment.Currency.CLP);
        pago.setGateway(Payment.Gateway.webpay);
        pago.setCreatedAt(Instant.now());
        when(payments.findById(41L)).thenReturn(Optional.of(pago));
        return pago;
    }

    private String firmaDe(Payment pago) {
        return firmas.sign("41", pago.getAmount().toPlainString(), "3");
    }

    /** La sesion del cobro en Transbank: con ella vuelve Webpay cuando se acaba el tiempo. */
    private String sesionDe(Payment pago) {
        return "DB" + firmas.sign("sesion-41", pago.getAmount().toPlainString(), "3").substring(0, 40);
    }

    @Test
    void confirmar_deja_el_aviso_encolado_para_ms_debt() {
        Payment pago = pagoAbierto();

        PaymentResponse confirmado = servicio.confirmPublic(41L, firmaDe(pago));

        assertEquals(Payment.Status.paid, confirmado.status());
        verify(avisos).save(any(DebtNotification.class));
    }

    @Test
    void confirmar_dos_veces_no_cobra_dos_veces() {
        Payment pago = pagoAbierto();
        String firma = firmaDe(pago);

        servicio.confirmPublic(41L, firma);
        servicio.confirmPublic(41L, firma);

        //  Un solo evento "paid" en el libro y un solo aviso.
        ArgumentCaptor<PaymentEvent> libro = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(eventos, times(1)).save(libro.capture());
        assertEquals(PaymentEvent.Type.paid, libro.getValue().getType());
        verify(avisos, times(1)).save(any(DebtNotification.class));
    }

    @Test
    void un_aviso_con_firma_falsa_no_se_aplica_pero_queda_anotado() {
        Payment pago = pagoAbierto();

        ApiException error = assertThrows(ApiException.class,
                () -> servicio.webhook(new WebhookRequest(41L, null, "wp-1", "firma-falsa"), null));

        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatus());
        assertEquals(Payment.Status.created, pago.getStatus());
        ArgumentCaptor<PaymentEvent> libro = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(eventos).save(libro.capture());
        assertEquals(PaymentEvent.Type.failed, libro.getValue().getType());
        assertFalse(libro.getValue().getSignatureOk());
    }

    @Test
    void cada_quien_ve_solo_sus_pagos() {
        pagoAbierto();
        JwtPrincipal otro = new JwtPrincipal("18905214-6", null, "DEBTOR", null, "18905214-6");
        JwtPrincipal otraEmpresa = new JwtPrincipal("9", "x@y.cl", "CREDITOR", "X", "77305118-6");

        assertEquals(41L, servicio.get(DEUDOR, 41L).id());
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ApiException.class, () -> servicio.get(otro, 41L)).getStatus());
        //  APOFYX opera la cartera, pero el acreedor del pago es Patrimonio.
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ApiException.class, () -> servicio.get(otraEmpresa, 41L)).getStatus());
    }

    // ------------------------------------------------------------------
    //  Khipu de verdad (con KHIPU_LLAVE)
    // ------------------------------------------------------------------

    private static final String KHIPU_ID = "gqzdy6chjne9";

    private PaymentResponse cobroConKhipu() {
        when(khipu.real()).thenReturn(true);
        when(khipu.crear(any(), any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new KhipuClient.Cobro(KHIPU_ID, "https://khipu.com/payment/info/" + KHIPU_ID));
        when(deudas.obtener(3L, null)).thenReturn(deudaDe(FELIPE, "CLP", "410000"));
        return servicio.checkout(DEUDOR, new CheckoutRequest(3L, null, "khipu"));
    }

    /** El pago recien abierto en Khipu, como lo guarda el checkout. */
    private Payment abierto() {
        Payment pago = new Payment();
        pago.setId(41L);
        pago.setDebtId(3L);
        pago.setDebtorRut(FELIPE);
        pago.setCreditorRut("76418902-7");
        pago.setAmount(new BigDecimal("410000"));
        pago.setAmountClp(410000L);
        pago.setCurrency(Payment.Currency.CLP);
        pago.setGateway(Payment.Gateway.khipu);
        pago.setStatus(Payment.Status.created);
        pago.setGatewayTxnId(KHIPU_ID);
        pago.setCreatedAt(Instant.now());
        when(khipu.real()).thenReturn(true);
        when(payments.findByGatewayAndGatewayTxnId(Payment.Gateway.khipu, KHIPU_ID)).thenReturn(Optional.of(pago));
        when(payments.findById(41L)).thenReturn(Optional.of(pago));
        return pago;
    }

    private static KhipuClient.Estado estado(String status, String detalle, String monto, String transaccion)
            throws Exception {
        return new KhipuClient.Estado(status, detalle, new BigDecimal(monto), "CLP", transaccion,
                new ObjectMapper().readTree("{\"status\":\"" + status + "\",\"status_detail\":\"" + detalle + "\"}"));
    }

    @Test
    void con_khipu_el_cobro_se_abre_en_khipu_con_el_monto_en_pesos() {
        PaymentResponse pago = cobroConKhipu();

        ArgumentCaptor<String> retorno = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> cancelado = ArgumentCaptor.forClass(String.class);
        verify(khipu).crear(org.mockito.ArgumentMatchers.eq("TB-41"), any(), org.mockito.ArgumentMatchers.eq(410000L),
                retorno.capture(), cancelado.capture(), org.mockito.ArgumentMatchers.isNull(), any());
        assertTrue(retorno.getValue().startsWith("http://localhost:8080/pasarela/41?sig="));
        assertTrue(cancelado.getValue().endsWith("&cancelado=1"));
        assertEquals("https://khipu.com/payment/info/" + KHIPU_ID, pago.checkoutUrl(), "se paga en la pagina de Khipu");
        assertFalse(pago.simulada());
    }

    @Test
    void al_volver_con_el_pago_conciliado_se_confirma_y_se_avisa_a_ms_debt() throws Exception {
        Payment pago = abierto();
        when(khipu.estado(KHIPU_ID)).thenReturn(estado("done", "normal", "410000.0000", "TB-41"));

        PaymentResponse respuesta = servicio.verificar(41L, firmaDe(pago));

        assertEquals(Payment.Status.paid, respuesta.status());
        assertEquals(KHIPU_ID, pago.getGatewayTxnId());
        verify(avisos).save(any(DebtNotification.class));
        ArgumentCaptor<PaymentEvent> evento = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(eventos).save(evento.capture());
        assertEquals(PaymentEvent.Type.paid, evento.getValue().getType());
        assertTrue(evento.getValue().getGatewayPayload().contains("done"), "se guarda lo que dijo Khipu");
    }

    @Test
    void mientras_khipu_verifica_la_transferencia_el_pago_sigue_abierto() throws Exception {
        Payment pago = abierto();
        when(khipu.estado(KHIPU_ID)).thenReturn(estado("verifying", "pending", "410000", "TB-41"));

        servicio.verificar(41L, firmaDe(pago));

        assertEquals(Payment.Status.created, pago.getStatus());
        verify(avisos, never()).save(any());
    }

    @Test
    void otro_monto_u_otra_transaccion_no_se_dan_por_pagados() throws Exception {
        Payment pago = abierto();
        when(khipu.estado(KHIPU_ID)).thenReturn(estado("done", "normal", "1", "TB-41"));
        servicio.verificar(41L, firmaDe(pago));
        assertEquals(Payment.Status.failed, pago.getStatus());

        Payment otro = abierto();
        when(khipu.estado(KHIPU_ID)).thenReturn(estado("done", "normal", "410000", "TB-99"));
        servicio.verificar(41L, firmaDe(otro));
        assertEquals(Payment.Status.failed, otro.getStatus());

        verify(avisos, never()).save(any());
    }

    @Test
    void rechazado_o_revertido_queda_fallido() throws Exception {
        Payment rechazado = abierto();
        when(khipu.estado(KHIPU_ID)).thenReturn(estado("done", "rejected-by-payer", "410000", "TB-41"));
        servicio.verificar(41L, firmaDe(rechazado));
        assertEquals(Payment.Status.failed, rechazado.getStatus());

        Payment revertido = abierto();
        when(khipu.estado(KHIPU_ID)).thenReturn(estado("done", "reversed", "410000", "TB-41"));
        servicio.verificar(41L, firmaDe(revertido));
        assertEquals(Payment.Status.failed, revertido.getStatus());
    }

    @Test
    void si_el_deudor_se_arrepiente_queda_fallido_salvo_que_haya_alcanzado_a_pagar() throws Exception {
        Payment arrepentido = abierto();
        when(khipu.estado(KHIPU_ID)).thenReturn(estado("pending", "pending", "410000", "TB-41"));
        when(khipu.anular(KHIPU_ID)).thenReturn(true);
        assertEquals(Payment.Status.failed, servicio.cancelar(41L, firmaDe(arrepentido)).status());
        verify(khipu).anular(KHIPU_ID);

        Payment alcanzo = abierto();
        when(khipu.estado(KHIPU_ID)).thenReturn(estado("done", "normal", "410000", "TB-41"));
        assertEquals(Payment.Status.paid, servicio.cancelar(41L, firmaDe(alcanzo)).status());
    }

    @Test
    void si_khipu_no_deja_anular_porque_justo_se_pago_el_pago_vale() throws Exception {
        Payment pago = abierto();
        when(khipu.estado(KHIPU_ID))
                .thenReturn(estado("pending", "pending", "410000", "TB-41"))
                .thenReturn(estado("done", "normal", "410000", "TB-41"));
        when(khipu.anular(KHIPU_ID)).thenReturn(false);

        assertEquals(Payment.Status.paid, servicio.cancelar(41L, firmaDe(pago)).status());
        verify(avisos).save(any(DebtNotification.class));
    }

    @Test
    void verificar_un_pago_ya_cerrado_no_le_pregunta_otra_vez_a_khipu() throws Exception {
        Payment pago = abierto();
        when(khipu.estado(KHIPU_ID)).thenReturn(estado("done", "normal", "410000", "TB-41"));

        servicio.verificar(41L, firmaDe(pago));
        servicio.verificar(41L, firmaDe(pago));

        verify(khipu, times(1)).estado(KHIPU_ID);
        assertEquals(Payment.Status.paid, pago.getStatus());
    }

    @Test
    void un_pago_de_khipu_no_se_confirma_desde_la_pagina_simulada() {
        Payment pago = abierto();

        ApiException error = assertThrows(ApiException.class, () -> servicio.confirmPublic(41L, firmaDe(pago)));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertEquals(Payment.Status.created, pago.getStatus());
    }

    @Test
    void sin_llave_khipu_sigue_simulada() {
        when(deudas.obtener(3L, null)).thenReturn(deudaDe(FELIPE, "CLP", "410000"));

        PaymentResponse pago = servicio.checkout(DEUDOR, new CheckoutRequest(3L, null, "khipu"));

        assertTrue(pago.simulada());
        assertTrue(pago.checkoutUrl().startsWith("http://localhost:8080/pasarela/41?sig="));
        verify(khipu, never()).crear(any(), any(), anyLong(), any(), any(), any(), any());
    }

    @Test
    void la_consulta_periodica_cierra_lo_pagado_y_vence_lo_abandonado() throws Exception {
        Payment pagado = abierto();
        Payment abandonado = new Payment();
        abandonado.setId(42L);
        abandonado.setDebtId(4L);
        abandonado.setAmount(new BigDecimal("1000"));
        abandonado.setAmountClp(1000L);
        abandonado.setGateway(Payment.Gateway.khipu);
        abandonado.setStatus(Payment.Status.created);
        abandonado.setGatewayTxnId("abandonado1");
        abandonado.setCreatedAt(Instant.now().minus(java.time.Duration.ofHours(1)));
        Payment sinRespuesta = new Payment();
        sinRespuesta.setId(43L);
        sinRespuesta.setGateway(Payment.Gateway.khipu);
        sinRespuesta.setStatus(Payment.Status.created);
        sinRespuesta.setGatewayTxnId("caido1");
        sinRespuesta.setCreatedAt(Instant.now().minus(java.time.Duration.ofHours(1)));
        when(payments.findByGatewayAndStatus(Payment.Gateway.khipu, Payment.Status.created))
                .thenReturn(List.of(pagado, abandonado, sinRespuesta));
        when(khipu.estado(KHIPU_ID)).thenReturn(estado("done", "normal", "410000", "TB-41"));
        when(khipu.estado("abandonado1")).thenReturn(estado("pending", "pending", "1000", "TB-42"));
        when(khipu.estado("caido1")).thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "Khipu no responde"));

        assertEquals(2, servicio.conciliarPendientes());

        assertEquals(Payment.Status.paid, pagado.getStatus());
        assertEquals(Payment.Status.expired, abandonado.getStatus());
        assertEquals(Payment.Status.created, sinRespuesta.getStatus(), "si Khipu no responde, se reintenta despues");
    }

    @Test
    void el_aviso_de_khipu_se_verifica_y_dispara_la_consulta() throws Exception {
        Payment pago = abierto();
        when(khipu.estado(KHIPU_ID)).thenReturn(estado("done", "normal", "410000", "TB-41"));
        String cuerpo = "{\"payment_id\":\"" + KHIPU_ID + "\",\"amount\":\"1\"}";
        String firma = "t=1711965600393,s=" + java.util.Base64.getEncoder()
                .encodeToString(firmaDeKhipu.firmar("1711965600393." + cuerpo));

        assertEquals(HttpStatus.UNAUTHORIZED, assertThrows(ApiException.class,
                () -> servicio.avisoDeKhipu(cuerpo, "t=1,s=otra", KHIPU_ID)).getStatus());
        assertEquals(Payment.Status.created, pago.getStatus());

        servicio.avisoDeKhipu(cuerpo, firma, KHIPU_ID);

        assertEquals(Payment.Status.paid, pago.getStatus(), "manda lo que dice Khipu, no el monto del aviso");
    }

    // ------------------------------------------------------------------
    //  Mientras la pasarela verifica: no cobrar dos veces, no perder un pago
    // ------------------------------------------------------------------

    private Payment pagadoAntes(Long id, Long... cuotas) {
        Payment pago = new Payment();
        pago.setId(id);
        pago.setDebtId(3L);
        pago.setStatus(Payment.Status.paid);
        pago.setCuotas(List.of(cuotas));
        return pago;
    }

    @Test
    void la_consulta_periodica_no_vence_lo_que_khipu_esta_verificando() throws Exception {
        Payment pago = abierto();
        pago.setCreatedAt(Instant.now().minus(java.time.Duration.ofHours(1)));
        when(payments.findByGatewayAndStatus(Payment.Gateway.khipu, Payment.Status.created)).thenReturn(List.of(pago));
        when(khipu.estado(KHIPU_ID)).thenReturn(estado("verifying", "pending", "410000", "TB-41"));

        assertEquals(0, servicio.conciliarPendientes());

        assertEquals(Payment.Status.created, pago.getStatus(), "el deudor ya pago: vencerlo dejaria la plata sin abonar");
        verify(khipu, never()).anular(any());
    }

    @Test
    void al_vencer_se_anula_en_khipu_para_que_no_se_pueda_pagar_despues() throws Exception {
        Payment pago = abierto();
        pago.setCreatedAt(Instant.now().minus(java.time.Duration.ofHours(1)));
        when(payments.findByGatewayAndStatus(Payment.Gateway.khipu, Payment.Status.created)).thenReturn(List.of(pago));
        when(khipu.estado(KHIPU_ID)).thenReturn(estado("pending", "pending", "410000", "TB-41"));
        when(khipu.anular(KHIPU_ID)).thenReturn(true);

        servicio.conciliarPendientes();

        verify(khipu).anular(KHIPU_ID);
        assertEquals(Payment.Status.expired, pago.getStatus());
    }

    @Test
    void un_vencido_que_khipu_concilia_despues_se_registra() throws Exception {
        Payment pago = abierto();
        pago.setStatus(Payment.Status.expired);
        when(payments.findByGatewayAndStatusAndCreatedAtAfter(eq(Payment.Gateway.khipu), eq(Payment.Status.expired),
                any())).thenReturn(List.of(pago));
        when(khipu.estado(KHIPU_ID)).thenReturn(estado("done", "normal", "410000", "TB-41"));

        assertEquals(1, servicio.revisarVencidos());

        assertEquals(Payment.Status.paid, pago.getStatus(), "esa plata se cobro: se abona");
        verify(avisos).save(any(DebtNotification.class));
    }

    @Test
    void un_vencido_sin_pagar_queda_vencido() throws Exception {
        Payment pago = abierto();
        pago.setStatus(Payment.Status.expired);
        when(payments.findByGatewayAndStatusAndCreatedAtAfter(eq(Payment.Gateway.khipu), eq(Payment.Status.expired),
                any())).thenReturn(List.of(pago));
        when(khipu.estado(KHIPU_ID)).thenReturn(estado("done", "rejected-by-payer", "410000", "TB-41"));

        assertEquals(0, servicio.revisarVencidos());

        assertEquals(Payment.Status.expired, pago.getStatus());
    }

    @Test
    void el_aviso_de_khipu_de_un_pago_ya_vencido_lo_registra() throws Exception {
        Payment pago = abierto();
        pago.setStatus(Payment.Status.expired);
        when(khipu.estado(KHIPU_ID)).thenReturn(estado("done", "normal", "410000", "TB-41"));
        String cuerpo = "{\"payment_id\":\"" + KHIPU_ID + "\"}";
        String firma = "t=1711965600393,s=" + java.util.Base64.getEncoder()
                .encodeToString(firmaDeKhipu.firmar("1711965600393." + cuerpo));

        servicio.avisoDeKhipu(cuerpo, firma, KHIPU_ID);

        assertEquals(Payment.Status.paid, pago.getStatus());
    }

    @Test
    void mientras_khipu_verifica_no_se_abre_otro_pago_por_la_misma_deuda() throws Exception {
        Payment enVerificacion = abierto();
        when(payments.findByDebtIdAndStatus(3L, Payment.Status.created)).thenReturn(List.of(enVerificacion));
        when(khipu.estado(KHIPU_ID)).thenReturn(estado("verifying", "pending", "410000", "TB-41"));
        when(deudas.obtener(3L, null)).thenReturn(deudaDe(FELIPE, "CLP", "410000"));

        ApiException error = assertThrows(ApiException.class,
                () -> servicio.checkout(DEUDOR, new CheckoutRequest(3L, null, "webpay")));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertTrue(error.getMessage().contains("en verificación en Khipu"), error.getMessage());
        verify(payments, never()).save(any());
        verify(khipu, never()).anular(any());
    }

    @Test
    void un_pago_abierto_sin_pagar_se_anula_al_abrir_otro() throws Exception {
        Payment olvidado = abierto();
        when(payments.findByDebtIdAndStatus(3L, Payment.Status.created)).thenReturn(List.of(olvidado));
        when(khipu.estado(KHIPU_ID)).thenReturn(estado("pending", "pending", "410000", "TB-41"));
        when(khipu.anular(KHIPU_ID)).thenReturn(true);
        when(deudas.obtener(3L, null)).thenReturn(deudaDe(FELIPE, "CLP", "410000"));

        PaymentResponse nuevo = servicio.checkout(DEUDOR, new CheckoutRequest(3L, null, "webpay"));

        verify(khipu).anular(KHIPU_ID);
        assertEquals(Payment.Status.expired, olvidado.getStatus(), "ya no se puede pagar: no se cobran los dos");
        assertEquals(Payment.Status.created, nuevo.status());
    }

    @Test
    void un_cobro_de_mercadopago_sin_pagar_se_vence_alla_al_abrir_otro() {
        Payment olvidado = abiertoEnMercadoPago();
        when(payments.findByDebtIdAndStatus(3L, Payment.Status.created)).thenReturn(List.of(olvidado));
        when(mercadopago.consultarPreferencia("pref_123")).thenReturn(preferencia(null));
        when(deudas.obtener(3L, null)).thenReturn(deudaDe(FELIPE, "CLP", "410000"));

        servicio.checkout(DEUDOR, new CheckoutRequest(3L, null, "webpay"));

        verify(mercadopago).vencerPreferencia("pref_123");
        assertEquals(Payment.Status.expired, olvidado.getStatus());
    }

    @Test
    void cuotas_ya_pagadas_que_ms_debt_todavia_no_abona_no_se_cobran_de_nuevo() {
        when(payments.findByDebtIdAndStatus(3L, Payment.Status.paid)).thenReturn(List.of(pagadoAntes(40L, 12L)));
        when(deudas.obtener(3L, null)).thenReturn(deudaDe(FELIPE, "CLP", "410000"));

        ApiException error = assertThrows(ApiException.class,
                () -> servicio.checkout(DEUDOR, new CheckoutRequest(3L, null, "webpay")));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        verify(payments, never()).save(any());
    }

    @Test
    void el_cobro_guarda_las_cuotas_que_cubre() {
        when(deudas.obtener(3L, List.of(12L, 13L))).thenReturn(
                new DebtClient.DebtSnapshot(3L, "76418902-7", FELIPE, "CLP", new BigDecimal("280000"), null,
                        List.of(12L, 13L), new BigDecimal("280000"), BigDecimal.ZERO));
        ArgumentCaptor<Payment> guardado = ArgumentCaptor.forClass(Payment.class);

        servicio.checkout(DEUDOR, new CheckoutRequest(3L, List.of(12L, 13L), "webpay"));

        verify(payments, org.mockito.Mockito.atLeastOnce()).save(guardado.capture());
        assertEquals(java.util.Set.of(12L, 13L), guardado.getValue().cuotas());
    }

    @Test
    void si_igual_se_pagan_dos_veces_las_mismas_cuotas_el_segundo_queda_para_devolver() throws Exception {
        Payment segundo = abierto();
        segundo.setCuotas(List.of(12L, 13L));
        when(payments.findByDebtIdAndStatus(3L, Payment.Status.paid)).thenReturn(List.of(pagadoAntes(40L, 13L)));
        when(khipu.estado(KHIPU_ID)).thenReturn(estado("done", "normal", "410000", "TB-41"));

        PaymentResponse respuesta = servicio.verificar(41L, firmaDe(segundo));

        assertEquals(Payment.Status.duplicated, respuesta.status());
        assertNull(segundo.getPaidAt());
        verify(avisos, never()).save(any());
        assertEquals(KHIPU_ID, respuesta.referenciaPasarela(), "con que transaccion devolverlo");
        assertEquals(FELIPE, respuesta.deudorRut(), "a quien devolverlo");
        ArgumentCaptor<PaymentEvent> evento = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(eventos).save(evento.capture());
        assertEquals(PaymentEvent.Type.duplicated, evento.getValue().getType());
    }

    @Test
    void la_empresa_ve_los_duplicados_de_la_cartera_que_le_dice_ms_debt() {
        JwtPrincipal agencia = new JwtPrincipal("1", "camila.reyes@apofyx.cl", "CREDITOR", "Camila", "77305118-6");
        Payment duplicado = abierto();
        duplicado.setStatus(Payment.Status.duplicated);
        when(deudas.cartera("77305118-6")).thenReturn(List.of(3L));
        when(payments.findByStatusAndDebtIdInOrderByCreatedAtDesc(Payment.Status.duplicated, List.of(3L)))
                .thenReturn(List.of(duplicado));

        List<PaymentResponse> paraDevolver = servicio.paraDevolver(agencia);

        assertEquals(1, paraDevolver.size(), "la agencia ve la cartera que entrego, aunque el acreedor sea otro");
        assertEquals(KHIPU_ID, paraDevolver.getFirst().referenciaPasarela());
        assertEquals(HttpStatus.FORBIDDEN,
                assertThrows(ApiException.class, () -> servicio.paraDevolver(DEUDOR)).getStatus());
    }

    @Test
    void registrar_devolucion_de_un_pago_duplicado() {
        JwtPrincipal agencia = new JwtPrincipal("1", "camila.reyes@apofyx.cl", "CREDITOR", "Camila", "77305118-6");
        Payment duplicado = abierto();
        duplicado.setStatus(Payment.Status.duplicated);
        when(payments.findById(41L)).thenReturn(Optional.of(duplicado));
        when(deudas.cartera("77305118-6")).thenReturn(List.of(duplicado.getDebtId()));

        PaymentResponse res = servicio.registrarDevolucion(agencia, 41L, "DEV-12345");

        assertEquals(Payment.Status.refunded, res.status());
        verify(payments).save(duplicado);
        verify(eventos).save(argThat(e -> e.getType() == PaymentEvent.Type.refunded && e.getSource() == PaymentEvent.Source.manual));
    }

    @Test
    void un_pago_no_duplicado_no_se_puede_devolver() {
        JwtPrincipal agencia = new JwtPrincipal("1", "camila.reyes@apofyx.cl", "CREDITOR", "Camila", "77305118-6");
        Payment pagado = abierto();
        pagado.setStatus(Payment.Status.paid);
        when(payments.findById(41L)).thenReturn(Optional.of(pagado));
        when(deudas.cartera("77305118-6")).thenReturn(List.of(pagado.getDebtId()));

        ApiException fallo = assertThrows(ApiException.class, () -> servicio.registrarDevolucion(agencia, 41L, "DEV-1"));
        assertEquals(HttpStatus.BAD_REQUEST, fallo.getStatus());
        assertEquals("Solo se puede registrar la devolucion de un pago duplicado", fallo.getMessage());
    }

    @Test
    void pagar_otras_cuotas_de_la_misma_deuda_no_es_duplicado() throws Exception {
        Payment segundo = abierto();
        segundo.setCuotas(List.of(13L));
        when(payments.findByDebtIdAndStatus(3L, Payment.Status.paid)).thenReturn(List.of(pagadoAntes(40L, 12L)));
        when(khipu.estado(KHIPU_ID)).thenReturn(estado("done", "normal", "410000", "TB-41"));

        assertEquals(Payment.Status.paid, servicio.verificar(41L, firmaDe(segundo)).status());
        assertNull(servicio.verificar(41L, firmaDe(segundo)).referenciaPasarela(), "solo el duplicado la muestra");
    }

    // ------------------------------------------------------------------
    //  Webpay de verdad (ambiente de integracion de Transbank)
    // ------------------------------------------------------------------

    private static final String TOKEN = "01ab8a4e5ba0b0b5d4c0f7c1e1f0e5b4a3c2d1e0f9a8b7c6d5e4f3a2b1c0d9e8";

    private PaymentResponse cobroConWebpay() {
        when(webpay.real()).thenReturn(true);
        when(webpay.createTransaction(any(), any(), anyLong(), any()))
                .thenReturn(new WebpayCreateResponse(TOKEN, "https://webpay3gint.transbank.cl/webpayserver/initTransaction"));
        when(webpay.paginaDePago()).thenReturn("https://webpay3gint.transbank.cl/webpayserver/initTransaction");
        when(deudas.obtener(3L, null)).thenReturn(deudaDe(FELIPE, "CLP", "410000"));
        return servicio.checkout(DEUDOR, new CheckoutRequest(3L, null, "webpay"));
    }

    /** El pago recien abierto en Webpay, como lo encuentra la vuelta por su token. */
    private Payment abiertoEnWebpay() {
        Payment pago = new Payment();
        pago.setId(41L);
        pago.setDebtId(3L);
        pago.setDebtorRut(FELIPE);
        pago.setCreditorRut("76418902-7");
        pago.setAmount(new BigDecimal("410000"));
        pago.setAmountClp(410000L);
        pago.setCurrency(Payment.Currency.CLP);
        pago.setGateway(Payment.Gateway.webpay);
        pago.setStatus(Payment.Status.created);
        pago.setGatewayTxnId(TOKEN);
        pago.setCreatedAt(Instant.now());
        when(webpay.real()).thenReturn(true);
        when(payments.findByGatewayAndGatewayTxnId(Payment.Gateway.webpay, TOKEN)).thenReturn(Optional.of(pago));
        when(payments.paraCerrarPorToken(Payment.Gateway.webpay, TOKEN)).thenReturn(Optional.of(pago));
        when(payments.findById(41L)).thenReturn(Optional.of(pago));
        when(payments.paraCerrar(41L)).thenReturn(Optional.of(pago));
        return pago;
    }

    /** Lo que responde la consulta de Transbank: sin vci, sin pagar; con vci, pagada y sin confirmar. */
    private static WebpayCommitResponse estadoDeWebpay(String estado, String vci) {
        return new WebpayCommitResponse(vci, 410000L, estado, "ORD41T123", "deuda-3", null, null, null, null,
                vci == null ? null : "VN", null, null, null, null);
    }

    private static WebpayCommitResponse confirmacion(String estado, Integer codigo, long monto, String orden) {
        return new WebpayCommitResponse("TSY", monto, estado, orden, "deuda-3",
                new WebpayCommitResponse.CardDetail("6623"), "1003", "2026-10-03T12:00:00Z", "1213", "VD",
                codigo, null, 0, null);
    }

    @Test
    void con_webpay_el_cobro_se_abre_en_transbank_y_vuelve_por_la_direccion_publica() {
        PaymentResponse pago = cobroConWebpay();

        ArgumentCaptor<String> orden = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> sesion = ArgumentCaptor.forClass(String.class);
        verify(webpay).createTransaction(orden.capture(), sesion.capture(),
                org.mockito.ArgumentMatchers.eq(410000L),
                org.mockito.ArgumentMatchers.eq("http://localhost:8080/api/payments/public/webpay/retorno"));
        assertTrue(orden.getValue().matches("ORD41T\\d+"), orden.getValue());
        assertTrue(orden.getValue().length() <= 26, "Transbank acepta hasta 26 caracteres");
        assertTrue(sesion.getValue().length() <= 61, "Transbank acepta hasta 61 caracteres");
        assertFalse(sesion.getValue().contains("deuda"), "la sesion no se adivina: " + sesion.getValue());
        assertTrue(sesion.getValue().matches("DB[0-9a-f]{40}"), "es una firma del pago: " + sesion.getValue());
        assertTrue(pago.checkoutUrl().startsWith("http://localhost:8080/api/payments/public/41/webpay?sig="));
        assertFalse(pago.simulada());
    }

    @Test
    void la_pagina_de_webpay_manda_el_token_por_post_como_pide_transbank() {
        Payment pago = abiertoEnWebpay();
        when(webpay.paginaDePago()).thenReturn("https://webpay3gint.transbank.cl/webpayserver/initTransaction");

        String pagina = servicio.paginaWebpay(41L, firmaDe(pago));

        assertTrue(pagina.contains("method=\"post\" action=\"https://webpay3gint.transbank.cl/webpayserver/initTransaction\""));
        assertTrue(pagina.contains("name=\"token_ws\" value=\"" + TOKEN + "\""));
    }

    @Test
    void la_vuelta_aprobada_confirma_con_transbank_y_avisa_a_ms_debt() {
        Payment pago = abiertoEnWebpay();
        when(webpay.commitTransaction(TOKEN)).thenReturn(confirmacion("AUTHORIZED", 0, 410000, "ORD41T123"));

        String destino = servicio.retornoWebpay(TOKEN, null, null, null);

        assertEquals(Payment.Status.paid, pago.getStatus());
        assertEquals(TOKEN, pago.getGatewayTxnId());
        assertTrue(destino.startsWith("http://localhost:8080/pasarela/41?sig="));
        verify(avisos).save(any(DebtNotification.class));
        ArgumentCaptor<PaymentEvent> evento = ArgumentCaptor.forClass(PaymentEvent.class);
        verify(eventos).save(evento.capture());
        assertEquals(PaymentEvent.Type.paid, evento.getValue().getType());
        assertTrue(evento.getValue().getGatewayPayload().contains("AUTHORIZED"), "se guarda lo que respondio Transbank");
        assertFalse(evento.getValue().getGatewayPayload().contains("\"authorized\""), "y nada que Transbank no haya mandado");
    }

    @Test
    void rechazada_sin_codigo_otro_monto_u_otra_orden_no_se_dan_por_pagadas() {
        List<WebpayCommitResponse> malas = List.of(
                confirmacion("FAILED", -1, 410000, "ORD41T123"),
                confirmacion("AUTHORIZED", null, 410000, "ORD41T123"),
                confirmacion("AUTHORIZED", 0, 1, "ORD41T123"),
                confirmacion("AUTHORIZED", 0, 410000, "ORD99T123"));
        for (WebpayCommitResponse mala : malas) {
            Payment pago = abiertoEnWebpay();
            when(webpay.commitTransaction(TOKEN)).thenReturn(mala);

            servicio.retornoWebpay(TOKEN, null, null, null);

            assertEquals(Payment.Status.failed, pago.getStatus(), mala.toString());
        }
        verify(avisos, never()).save(any());
    }

    @Test
    void si_el_deudor_anula_o_se_le_acaba_el_tiempo_no_se_confirma_nada() {
        Payment anulado = abiertoEnWebpay();
        servicio.retornoWebpay(null, TOKEN, "ORD41T123", null);
        assertEquals(Payment.Status.failed, anulado.getStatus());

        //  Un error en el formulario de Webpay trae los dos tokens: tampoco se confirma.
        Payment conError = abiertoEnWebpay();
        servicio.retornoWebpay(TOKEN, TOKEN, "ORD41T123", null);
        assertEquals(Payment.Status.failed, conError.getStatus());

        //  Por tiempo, Webpay devuelve solo la orden de compra.
        Payment vencido = abiertoEnWebpay();
        servicio.retornoWebpay(null, null, "ORD41T123", sesionDe(vencido));
        assertEquals(Payment.Status.failed, vencido.getStatus());

        verify(webpay, never()).commitTransaction(any());
    }

    @Test
    void volver_dos_veces_no_confirma_dos_veces() {
        Payment pago = abiertoEnWebpay();
        when(webpay.commitTransaction(TOKEN)).thenReturn(confirmacion("AUTHORIZED", 0, 410000, "ORD41T123"));

        servicio.retornoWebpay(TOKEN, null, null, null);
        String segunda = servicio.retornoWebpay(TOKEN, null, null, null);

        verify(webpay, times(1)).commitTransaction(TOKEN);
        assertEquals(Payment.Status.paid, pago.getStatus());
        assertTrue(segunda.startsWith("http://localhost:8080/pasarela/41?sig="), "la segunda vuelta muestra el resultado");
    }

    @Test
    void si_transbank_no_responde_al_confirmar_el_pago_sigue_abierto() {
        Payment pago = abiertoEnWebpay();
        when(webpay.commitTransaction(TOKEN)).thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "Webpay no confirmo"));

        String destino = servicio.retornoWebpay(TOKEN, null, null, null);

        assertEquals(Payment.Status.created, pago.getStatus(), "no se marca fallido: podria haber quedado confirmado");
        assertTrue(destino.startsWith("http://localhost:8080/pasarela/41?sig="));
    }

    @Test
    void un_token_que_no_es_de_ningun_pago_es_404_y_no_se_confirma() {
        when(webpay.real()).thenReturn(true);
        when(payments.findByGatewayAndGatewayTxnId(any(), any())).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ApiException.class, () -> servicio.retornoWebpay("otro", null, null, null)).getStatus());
        verify(webpay, never()).commitTransaction(any());
    }

    @Test
    void un_pago_de_webpay_no_se_confirma_desde_la_pagina_simulada() {
        Payment pago = abiertoEnWebpay();

        ApiException error = assertThrows(ApiException.class, () -> servicio.confirmPublic(41L, firmaDe(pago)));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertTrue(error.getMessage().contains("Webpay"));
        assertEquals(Payment.Status.created, pago.getStatus());
    }

    @Test
    void un_cobro_de_webpay_abandonado_queda_vencido_y_uno_reciente_no() {
        when(webpay.estado(TOKEN)).thenReturn(estadoDeWebpay("INITIALIZED", null));
        Payment viejo = abiertoEnWebpay();
        viejo.setCreatedAt(Instant.now().minus(java.time.Duration.ofHours(1)));
        assertTrue(servicio.conciliarWebpay(41L));
        assertEquals(Payment.Status.expired, viejo.getStatus());

        Payment reciente = abiertoEnWebpay();
        assertFalse(servicio.conciliarWebpay(41L), "dentro del plazo de Transbank sigue abierto");
        assertEquals(Payment.Status.created, reciente.getStatus());
        verify(avisos, never()).save(any());
    }

    // ---- Webpay segun la documentacion de Transbank (issue #75) --------------------

    @Test
    void la_pagina_de_webpay_manda_el_token_una_sola_vez() {
        Payment pago = abiertoEnWebpay();
        when(webpay.paginaDePago()).thenReturn("https://webpay3gint.transbank.cl/webpayserver/initTransaction");

        String primera = servicio.paginaWebpay(41L, firmaDe(pago));
        String segunda = servicio.paginaWebpay(41L, firmaDe(pago));

        assertTrue(primera.contains("name=\"token_ws\" value=\"" + TOKEN + "\""));
        org.junit.jupiter.api.Assertions.assertNotNull(pago.getRedirectedAt(), "queda cuando se llevo al deudor");
        assertFalse(segunda.contains(TOKEN), "la segunda vez no se manda el token: seria el Error 21");
        assertTrue(segunda.contains("Webpay ya se abrió para este pago"));
        assertTrue(segunda.contains("http://localhost:8080/pasarela/41?sig="), "y lleva de vuelta al portal");
    }

    @Test
    void la_pagina_de_un_cobro_ya_cerrado_lleva_al_resultado_sin_el_token() {
        Payment pago = abiertoEnWebpay();
        pago.setStatus(Payment.Status.paid);

        String pagina = servicio.paginaWebpay(41L, firmaDe(pago));

        assertFalse(pagina.contains(TOKEN));
        assertTrue(pagina.contains("http://localhost:8080/pasarela/41?sig="));
    }

    @Test
    void si_el_deudor_pago_y_no_volvio_ms_payments_lo_confirma() {
        Payment pago = abiertoEnWebpay();
        when(webpay.estado(TOKEN)).thenReturn(estadoDeWebpay("INITIALIZED", "TSY"));
        when(webpay.commitTransaction(TOKEN)).thenReturn(confirmacion("AUTHORIZED", 0, 410000, "ORD41T123"));

        assertTrue(servicio.conciliarWebpay(41L));

        assertEquals(Payment.Status.paid, pago.getStatus());
        verify(webpay).commitTransaction(TOKEN);
        verify(avisos).save(any(DebtNotification.class));
    }

    @Test
    void si_transbank_ya_lo_confirmo_se_registra_sin_volver_a_confirmar() {
        Payment pago = abiertoEnWebpay();
        when(webpay.estado(TOKEN)).thenReturn(confirmacion("AUTHORIZED", 0, 410000, "ORD41T123"));

        assertTrue(servicio.conciliarWebpay(41L));

        assertEquals(Payment.Status.paid, pago.getStatus());
        verify(webpay, never()).commitTransaction(any());
    }

    @Test
    void fallido_reversado_o_anulado_en_transbank_queda_fallido() {
        for (String estado : List.of("FAILED", "REVERSED", "NULLIFIED")) {
            Payment pago = abiertoEnWebpay();
            when(webpay.estado(TOKEN)).thenReturn(confirmacion(estado, -1, 410000, "ORD41T123"));

            assertTrue(servicio.conciliarWebpay(41L), estado);

            assertEquals(Payment.Status.failed, pago.getStatus(), estado);
        }
        verify(avisos, never()).save(any());
    }

    @Test
    void sin_pagar_y_dentro_del_plazo_sigue_abierto_sin_confirmar_nada() {
        Payment pago = abiertoEnWebpay();
        when(webpay.estado(TOKEN)).thenReturn(estadoDeWebpay("INITIALIZED", null));

        assertFalse(servicio.conciliarWebpay(41L));

        assertEquals(Payment.Status.created, pago.getStatus());
        verify(webpay, never()).commitTransaction(any());
    }

    @Test
    void si_transbank_no_responde_sigue_abierto_y_un_dia_despues_vence() {
        Payment pago = abiertoEnWebpay();
        when(webpay.estado(TOKEN)).thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "Webpay no respondio"));

        pago.setCreatedAt(Instant.now().minus(java.time.Duration.ofHours(1)));
        assertFalse(servicio.conciliarWebpay(41L));
        assertEquals(Payment.Status.created, pago.getStatus(), "sin respuesta no se sabe: se pregunta de nuevo");

        pago.setCreatedAt(Instant.now().minus(java.time.Duration.ofHours(25)));
        assertTrue(servicio.conciliarWebpay(41L));
        assertEquals(Payment.Status.expired, pago.getStatus(), "un cobro no queda abierto para siempre");
    }

    @Test
    void un_cobro_vencido_que_transbank_alcanzo_a_confirmar_se_registra() {
        Payment pago = abiertoEnWebpay();
        pago.setStatus(Payment.Status.expired);
        when(webpay.estado(TOKEN)).thenReturn(confirmacion("AUTHORIZED", 0, 410000, "ORD41T123"));

        assertTrue(servicio.conciliarWebpay(41L));

        assertEquals(Payment.Status.paid, pago.getStatus());
    }

    @Test
    void solo_se_revisan_los_cobros_de_un_webpay_real() {
        when(webpay.real()).thenReturn(false);
        assertTrue(servicio.webpayPorRevisar(false).isEmpty());
        assertTrue(servicio.webpayPorRevisar(true).isEmpty());

        Payment pago = abiertoEnWebpay();
        when(payments.findByGatewayAndStatus(Payment.Gateway.webpay, Payment.Status.created)).thenReturn(List.of(pago));
        assertEquals(List.of(41L), servicio.webpayPorRevisar(false));

        Payment khipu = abiertoEnWebpay();
        khipu.setGateway(Payment.Gateway.khipu);
        assertFalse(servicio.conciliarWebpay(41L), "un cobro de otra pasarela no se le pregunta a Transbank");
        verify(webpay, never()).estado(any());
    }

    @Test
    void al_abrir_otro_cobro_si_el_webpay_anterior_ya_se_pago_se_registra_y_no_se_cobra_dos_veces() {
        Payment anterior = abiertoEnWebpay();
        anterior.setCuotas(List.of(12L));
        when(payments.findByDebtIdAndStatus(3L, Payment.Status.created)).thenReturn(List.of(anterior));
        when(payments.findByDebtIdAndStatus(3L, Payment.Status.paid)).thenAnswer(llamada ->
                anterior.getStatus() == Payment.Status.paid ? List.of(anterior) : List.of());
        when(webpay.estado(TOKEN)).thenReturn(estadoDeWebpay("INITIALIZED", "TSY"));
        when(webpay.commitTransaction(TOKEN)).thenReturn(confirmacion("AUTHORIZED", 0, 410000, "ORD41T123"));
        when(deudas.obtener(3L, null)).thenReturn(deudaDe(FELIPE, "CLP", "410000"));

        ApiException error = assertThrows(ApiException.class,
                () -> servicio.checkout(DEUDOR, new CheckoutRequest(3L, null, "webpay")));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertEquals(Payment.Status.paid, anterior.getStatus(), "lo que pago en la ventana anterior quedo registrado");
        verify(webpay, never()).createTransaction(any(), any(), anyLong(), any());
    }

    @Test
    void al_abrir_otro_cobro_el_webpay_anterior_sin_pagar_queda_vencido() {
        Payment anterior = abiertoEnWebpay();
        when(payments.findByDebtIdAndStatus(3L, Payment.Status.created)).thenReturn(List.of(anterior));
        when(webpay.estado(TOKEN)).thenReturn(estadoDeWebpay("INITIALIZED", null));
        when(webpay.createTransaction(any(), any(), anyLong(), any()))
                .thenReturn(new WebpayCreateResponse("tok-nuevo", "https://webpay3gint.transbank.cl/webpayserver/initTransaction"));
        when(deudas.obtener(3L, null)).thenReturn(deudaDe(FELIPE, "CLP", "410000"));

        servicio.checkout(DEUDOR, new CheckoutRequest(3L, null, "webpay"));

        assertEquals(Payment.Status.expired, anterior.getStatus(),
                "si igual pagara en esa ventana no se confirmaria: no se le cobra dos veces");
    }

    @Test
    void si_transbank_no_responde_al_abrir_otro_el_anterior_se_deja() {
        Payment anterior = abiertoEnWebpay();
        when(payments.findByDebtIdAndStatus(3L, Payment.Status.created)).thenReturn(List.of(anterior));
        when(webpay.estado(TOKEN)).thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "Webpay no respondio"));
        when(webpay.createTransaction(any(), any(), anyLong(), any()))
                .thenReturn(new WebpayCreateResponse("tok-nuevo", "https://webpay3gint.transbank.cl/webpayserver/initTransaction"));
        when(deudas.obtener(3L, null)).thenReturn(deudaDe(FELIPE, "CLP", "410000"));

        servicio.checkout(DEUDOR, new CheckoutRequest(3L, null, "webpay"));

        assertEquals(Payment.Status.created, anterior.getStatus(), "sin saber si se pago, no se vence a ciegas");
    }

    @Test
    void el_cobro_dice_hasta_cuando_se_puede_pagar() {
        PaymentResponse webpayReal = cobroConWebpay();
        assertEquals(java.time.Duration.ofMinutes(15),
                java.time.Duration.between(webpayReal.createdAt(), webpayReal.venceA()), "lo que da Transbank");

        when(khipu.real()).thenReturn(true);
        when(khipu.crear(any(), any(), anyLong(), any(), any(), any(), any()))
                .thenReturn(new KhipuClient.Cobro("pago-khipu", "https://khipu.com/payment/info/pago-khipu"));
        PaymentResponse conKhipu = servicio.checkout(DEUDOR, new CheckoutRequest(3L, null, "khipu"));
        assertEquals(java.time.Duration.ofMinutes(30),
                java.time.Duration.between(conKhipu.createdAt(), conKhipu.venceA()));

        when(khipu.real()).thenReturn(false);
        PaymentResponse simulada = servicio.checkout(DEUDOR, new CheckoutRequest(3L, null, "khipu"));
        assertNull(simulada.venceA(), "la simulacion no vence");
    }

    @Test
    void en_modo_simulado_webpay_no_sale_a_transbank() {
        when(deudas.obtener(3L, null)).thenReturn(deudaDe(FELIPE, "CLP", "410000"));

        PaymentResponse pago = servicio.checkout(DEUDOR, new CheckoutRequest(3L, null, "webpay"));

        assertTrue(pago.simulada());
        assertTrue(pago.checkoutUrl().startsWith("http://localhost:8080/pasarela/41?sig="));
        verify(webpay, never()).createTransaction(any(), any(), anyLong(), any());
    }

    @Test
    void checkout_con_mercadopago_crea_preferencia_y_devuelve_init_point() {
        when(deudas.obtener(3L, null)).thenReturn(deudaDe(FELIPE, "CLP", "410000"));
        when(mercadopago.real()).thenReturn(true);
        when(mercadopago.testMode()).thenReturn(true);
        when(mercadopago.crearPreferencia(any(), any(), anyLong(), any(), any(), any()))
                .thenReturn(new com.tbridge.payments.client.MercadoPagoClient.Preferencia(
                        "pref_123", "https://mp.test/init", "https://sandbox.mp.test/init"));

        PaymentResponse pago = servicio.checkout(DEUDOR, new CheckoutRequest(3L, null, "mercadopago"));

        assertFalse(pago.simulada());
        assertEquals("https://mp.test/init", pago.checkoutUrl(), "Mercado Pago cerro el sandbox: siempre init_point");
        ArgumentCaptor<java.time.OffsetDateTime> vence = ArgumentCaptor.forClass(java.time.OffsetDateTime.class);
        verify(mercadopago).crearPreferencia(eq("41"), any(), eq(410000L), any(), any(), vence.capture());
        assertTrue(vence.getValue().isAfter(java.time.OffsetDateTime.now().plusMinutes(29)), "vence en 30 minutos");
    }

    @Test
    void retorno_mercadopago_aprobado_confirma_el_pago() {
        Payment pago = new Payment();
        pago.setId(41L);
        pago.setDebtId(3L);
        pago.setAmount(new BigDecimal("410000"));
        pago.setCurrency(Payment.Currency.CLP);
        pago.setAmountClp(410000L);
        pago.setGateway(Payment.Gateway.mercadopago);
        pago.setStatus(Payment.Status.created);
        pago.setGatewayTxnId("pref_123");

        when(payments.findById(41L)).thenReturn(Optional.of(pago));
        when(mercadopago.consultarPago("pay_999"))
                .thenReturn(new com.tbridge.payments.client.MercadoPagoClient.PagoInfo(
                        999L, "approved", "accredited", new BigDecimal("410000"), "CLP", "41", null));

        String destino = servicio.retornoMercadoPago("pay_999", "approved", "approved", "41", "pref_123");

        assertEquals(Payment.Status.paid, pago.getStatus());
        assertTrue(destino.startsWith("http://localhost:8080/pasarela/41?sig="));
        verify(avisos).save(any());
    }

    // ------------------------------------------------------------------
    //  Mercado Pago: conciliar sin que el deudor vuelva al portal
    // ------------------------------------------------------------------

    private Payment abiertoEnMercadoPago() {
        Payment pago = new Payment();
        pago.setId(41L);
        pago.setDebtId(3L);
        pago.setDebtorRut(FELIPE);
        pago.setCreditorRut("76418902-7");
        pago.setAmount(new BigDecimal("410000"));
        pago.setAmountClp(410000L);
        pago.setCurrency(Payment.Currency.CLP);
        pago.setGateway(Payment.Gateway.mercadopago);
        pago.setStatus(Payment.Status.created);
        //  Al abrir el cobro se guardo la preferencia, no el pago.
        pago.setGatewayTxnId("pref_123");
        pago.setCreatedAt(Instant.now());
        when(mercadopago.real()).thenReturn(true);
        when(payments.findById(41L)).thenReturn(Optional.of(pago));
        return pago;
    }

    private static MercadoPagoClient.PagoInfo pagoEnMp(String status, String monto, String referencia) {
        return new MercadoPagoClient.PagoInfo(999L, status, "accredited", new BigDecimal(monto), "CLP",
                referencia, null);
    }

    private static MercadoPagoClient.EstadoPreferencia preferencia(String estadoPago) {
        MercadoPagoClient.PagoDePreferencia p = estadoPago == null ? null
                : new MercadoPagoClient.PagoDePreferencia(999L, estadoPago, null);
        return new MercadoPagoClient.EstadoPreferencia("pref_123", "41", 410000L, p);
    }

    @Test
    void verificar_concilia_el_pago_de_mercadopago_preguntando_a_la_preferencia() {
        Payment pago = abiertoEnMercadoPago();
        when(mercadopago.consultarPreferencia("pref_123")).thenReturn(preferencia("approved"));
        when(mercadopago.consultarPago("999")).thenReturn(pagoEnMp("approved", "410000", "41"));

        PaymentResponse respuesta = servicio.verificar(41L, firmaDe(pago));

        assertEquals(Payment.Status.paid, respuesta.status());
        verify(avisos).save(any(DebtNotification.class));
    }

    @Test
    void la_consulta_periodica_cierra_lo_pagado_en_mercadopago_y_vence_lo_abandonado() {
        Payment pagado = abiertoEnMercadoPago();
        Payment abandonado = new Payment();
        abandonado.setId(42L);
        abandonado.setAmount(new BigDecimal("1000"));
        abandonado.setAmountClp(1000L);
        abandonado.setGateway(Payment.Gateway.mercadopago);
        abandonado.setStatus(Payment.Status.created);
        abandonado.setGatewayTxnId("pref_abandonada");
        abandonado.setCreatedAt(Instant.now().minus(java.time.Duration.ofHours(1)));
        when(payments.findByGatewayAndStatus(Payment.Gateway.mercadopago, Payment.Status.created))
                .thenReturn(List.of(pagado, abandonado));
        when(mercadopago.consultarPreferencia("pref_123")).thenReturn(preferencia("approved"));
        when(mercadopago.consultarPago("999")).thenReturn(pagoEnMp("approved", "410000", "41"));
        when(mercadopago.consultarPreferencia("pref_abandonada")).thenReturn(preferencia(null));

        assertEquals(2, servicio.conciliarPendientes());

        assertEquals(Payment.Status.paid, pagado.getStatus(), "sin que el deudor vuelva al portal");
        assertEquals(Payment.Status.expired, abandonado.getStatus());
        verify(khipu, never()).estado(any());
    }

    @Test
    void la_consulta_periodica_no_vence_un_pago_de_mercadopago_en_revision() {
        Payment pago = abiertoEnMercadoPago();
        pago.setCreatedAt(Instant.now().minus(java.time.Duration.ofHours(1)));
        when(payments.findByGatewayAndStatus(Payment.Gateway.mercadopago, Payment.Status.created))
                .thenReturn(List.of(pago));
        when(mercadopago.consultarPreferencia("pref_123")).thenReturn(preferencia("in_process"));
        when(mercadopago.consultarPago("999")).thenReturn(pagoEnMp("in_process", "410000", "41"));

        servicio.conciliarPendientes();

        assertEquals(Payment.Status.created, pago.getStatus(), "Mercado Pago todavia lo puede aprobar");
    }

    @Test
    void un_vencido_que_mercadopago_aprueba_despues_se_registra() {
        Payment pago = abiertoEnMercadoPago();
        pago.setStatus(Payment.Status.expired);
        when(payments.findByGatewayAndStatusAndCreatedAtAfter(eq(Payment.Gateway.mercadopago),
                eq(Payment.Status.expired), any())).thenReturn(List.of(pago));
        when(mercadopago.consultarPreferencia("pref_123")).thenReturn(preferencia("approved"));
        when(mercadopago.consultarPago("999")).thenReturn(pagoEnMp("approved", "410000", "41"));

        assertEquals(1, servicio.revisarVencidos());

        assertEquals(Payment.Status.paid, pago.getStatus());
    }

    @Test
    void sin_token_de_mercadopago_la_consulta_periodica_no_le_pregunta() {
        when(payments.findByGatewayAndStatus(any(), any())).thenReturn(List.of());

        assertEquals(0, servicio.conciliarPendientes());

        verify(mercadopago, never()).consultarPreferencia(any());
    }

    @Test
    void mientras_el_deudor_no_paga_en_mercadopago_el_pago_sigue_abierto() {
        Payment pago = abiertoEnMercadoPago();
        when(mercadopago.consultarPreferencia("pref_123")).thenReturn(preferencia(null));

        PaymentResponse respuesta = servicio.verificar(41L, firmaDe(pago));

        assertEquals(Payment.Status.created, respuesta.status());
        verify(mercadopago, never()).consultarPago(any());
        verify(avisos, never()).save(any());
    }

    @Test
    void una_tarjeta_rechazada_en_mercadopago_deja_el_cobro_abierto_para_reintentar() {
        Payment pago = abiertoEnMercadoPago();
        when(mercadopago.consultarPreferencia("pref_123")).thenReturn(preferencia("rejected"));
        when(mercadopago.consultarPago("999")).thenReturn(pagoEnMp("rejected", "410000", "41"));

        //  El deudor sigue en Mercado Pago y puede probar con otra tarjeta: si
        //  aca quedara fallido, ese segundo pago no se registraria.
        assertEquals(Payment.Status.created, servicio.verificar(41L, firmaDe(pago)).status());
        verify(avisos, never()).save(any());
    }

    @Test
    void un_pago_de_otro_cobro_no_cierra_este_pago() {
        Payment pago = abiertoEnMercadoPago();
        when(mercadopago.consultarPreferencia("pref_123")).thenReturn(preferencia("approved"));
        //  Pagado, pero por otro monto y otra referencia: no es este cobro.
        when(mercadopago.consultarPago("999")).thenReturn(pagoEnMp("approved", "1", "99"));

        assertEquals(Payment.Status.failed, servicio.verificar(41L, firmaDe(pago)).status());
    }

    @Test
    void si_mercadopago_no_responde_el_pago_queda_abierto_para_reintentar() {
        Payment pago = abiertoEnMercadoPago();
        when(mercadopago.consultarPreferencia("pref_123"))
                .thenThrow(new ApiException(HttpStatus.BAD_GATEWAY, "Mercado Pago no responde"));

        PaymentResponse respuesta = servicio.verificar(41L, firmaDe(pago));

        assertEquals(Payment.Status.created, respuesta.status());
        verify(avisos, never()).save(any());
    }
}
