package com.tbridge.payments.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tbridge.payments.exception.ApiException;
import com.tbridge.payments.security.JwtPrincipal;
import com.tbridge.payments.client.DebtClient;
import com.tbridge.payments.client.KhipuClient;
import com.tbridge.payments.client.MercadoPagoClient;
import com.tbridge.payments.client.WebpayClient;
import com.tbridge.payments.dto.gateway.WebpayCommitResponse;
import com.tbridge.payments.dto.gateway.WebpayCreateResponse;
import com.tbridge.payments.dto.request.CheckoutRequest;
import com.tbridge.payments.dto.response.PaymentResponse;
import com.tbridge.payments.model.DebtNotification;
import com.tbridge.payments.model.Payment;
import com.tbridge.payments.repository.DebtNotificationRepository;
import com.tbridge.payments.repository.PaymentEventRepository;
import com.tbridge.payments.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Duration;
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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Lo que alguien podria intentar contra las pasarelas, y lo que lo impide.
 *
 * <p>Las defensas de siempre tienen sus pruebas donde estan: el monto lo pone
 * ms-debt y no la peticion ({@code PaymentServiceTest}), un campo que no existe
 * se rechaza ({@code PaymentControllerTest}), nadie paga la deuda de otro, las
 * firmas de Khipu y de los avisos ({@code FirmaDeKhipuTest},
 * {@code WebhookVerifierTest}). Aca van las de Webpay y las que cruzan la vuelta
 * del deudor con la consulta periodica.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SeguridadDePasarelasTest {

    private static final String FELIPE = "16482337-7";
    private static final JwtPrincipal DEUDOR = new JwtPrincipal(FELIPE, null, "DEBTOR", null, FELIPE);
    private static final String TOKEN = "01ab23cd45ef";

    @Mock private PaymentRepository payments;
    @Mock private PaymentEventRepository eventos;
    @Mock private DebtNotificationRepository avisos;
    @Mock private DebtClient deudas;
    @Mock private UfService uf;
    @Mock private KhipuClient khipu;
    @Mock private WebpayClient webpay;
    @Mock private MercadoPagoClient mercadopago;

    private final WebhookVerifier firmas = new WebhookVerifier("secreto-de-prueba");
    private PaymentService servicio;

    @BeforeEach
    void preparar() {
        servicio = new PaymentService(payments, eventos, avisos, firmas, deudas, uf, khipu,
                new FirmaDeKhipu("secreto-de-khipu"), webpay, mercadopago, "http://localhost:8080/", "",
                Duration.ofMinutes(30), Duration.ofMinutes(30));
        when(payments.save(any())).thenAnswer(llamada -> {
            Payment pago = llamada.getArgument(0);
            if (pago.getId() == null) {
                pago.setId(41L);
            }
            return pago;
        });
        when(avisos.findByPaymentId(any())).thenReturn(Optional.empty());
        when(webpay.real()).thenReturn(true);
        when(webpay.plazoDePago()).thenReturn(Duration.ofMinutes(15));
        when(webpay.paginaDePago()).thenReturn("https://webpay3gint.transbank.cl/webpayserver/initTransaction");
    }

    private Payment abiertoEnWebpay(String token) {
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
        pago.setGatewayTxnId(token);
        pago.setCreatedAt(Instant.now());
        when(payments.findById(41L)).thenReturn(Optional.of(pago));
        when(payments.paraCerrar(41L)).thenReturn(Optional.of(pago));
        when(payments.paraCerrarPorToken(Payment.Gateway.webpay, token)).thenReturn(Optional.of(pago));
        return pago;
    }

    private String firmaDe(String id) {
        return firmas.sign(id, "410000", "3");
    }

    private String sesionDe(Payment pago) {
        return "DB" + firmas.sign("sesion-41", pago.getAmount().toPlainString(), "3").substring(0, 40);
    }

    private static WebpayCommitResponse transbank(String estado, Integer codigo, String vci, long monto, String orden) {
        return new WebpayCommitResponse(vci, monto, estado, orden, "sesion", null, null, null,
                codigo == null ? null : "1213", vci == null ? null : "VN", codigo, null, null, null);
    }

    @Test
    void la_pagina_de_webpay_escapa_lo_que_muestra() {
        abiertoEnWebpay("\"><script>alert(1)</script>");

        String pagina = servicio.paginaWebpay(41L, firmaDe("41"));

        assertFalse(pagina.contains("<script>alert(1)</script>"), "un token raro no se ejecuta en la pagina");
        assertTrue(pagina.contains("&quot;&gt;&lt;script&gt;"));
    }

    @Test
    void con_la_firma_de_otro_pago_no_se_abre_webpay_ni_se_gasta_el_token() {
        Payment pago = abiertoEnWebpay(TOKEN);

        ApiException error = assertThrows(ApiException.class, () -> servicio.paginaWebpay(41L, firmaDe("42")));

        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatus());
        assertNull(pago.getRedirectedAt(), "el token sigue sin usar: el deudor puede pagar igual");
        assertTrue(servicio.paginaWebpay(41L, firmaDe("41")).contains(TOKEN));
    }

    @Test
    void una_vuelta_por_tiempo_con_una_sesion_inventada_no_hace_fallar_el_cobro_de_otro() {
        Payment pago = abiertoEnWebpay(TOKEN);

        //  La orden de compra lleva el numero del pago: se adivina. La sesion no.
        for (String inventada : new String[] {null, "", "deuda-3", "DB0000000000000000000000000000000000000000"}) {
            ApiException error = assertThrows(ApiException.class,
                    () -> servicio.retornoWebpay(null, null, "ORD41T123", inventada));
            assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
            assertEquals(Payment.Status.created, pago.getStatus(), "con la sesion " + inventada);
        }

        servicio.retornoWebpay(null, null, "ORD41T123", sesionDe(pago));
        assertEquals(Payment.Status.failed, pago.getStatus(), "con la sesion verdadera, si: se le acabo el tiempo");
    }

    @Test
    void la_respuesta_del_cobro_no_muestra_el_token_de_la_pasarela() throws Exception {
        when(webpay.createTransaction(any(), any(), anyLong(), any()))
                .thenReturn(new WebpayCreateResponse(TOKEN, "https://webpay3gint.transbank.cl/webpayserver/initTransaction"));
        when(deudas.obtenerParaCheckout(3L, null, "16482337-7")).thenReturn(new DebtClient.DebtSnapshot(3L, "76418902-7", FELIPE, "CLP",
                new BigDecimal("410000"), 12L, List.of(12L), new BigDecimal("410000"), BigDecimal.ZERO));

        PaymentResponse pago = servicio.checkout(DEUDOR, new CheckoutRequest(3L, null, "webpay"));
        String json = new ObjectMapper().registerModule(new JavaTimeModule()).writeValueAsString(pago);

        assertNull(pago.referenciaPasarela());
        assertFalse(json.contains(TOKEN), "el token va solo en la pagina que lo manda a Webpay: " + json);
    }

    @Test
    void un_cobro_vencido_que_se_pago_sin_confirmar_no_se_confirma_para_no_cobrar_dos_veces() {
        Payment pago = abiertoEnWebpay(TOKEN);
        pago.setStatus(Payment.Status.expired);
        when(webpay.estado(TOKEN)).thenReturn(transbank("INITIALIZED", null, "TSY", 410000, "ORD41T123"));

        assertFalse(servicio.conciliarWebpay(41L));

        assertEquals(Payment.Status.expired, pago.getStatus(), "sin confirmar, Transbank lo reversa");
        verify(webpay, never()).commitTransaction(any());
    }

    @Test
    void la_consulta_no_acepta_otro_monto_ni_otra_orden() {
        for (WebpayCommitResponse mala : List.of(
                transbank("AUTHORIZED", 0, "TSY", 1, "ORD41T123"),
                transbank("AUTHORIZED", 0, "TSY", 410000, "ORD99T123"),
                transbank("AUTHORIZED", null, "TSY", 410000, "ORD41T123"))) {
            Payment pago = abiertoEnWebpay(TOKEN);
            when(webpay.estado(TOKEN)).thenReturn(mala);

            servicio.conciliarWebpay(41L);

            assertEquals(Payment.Status.failed, pago.getStatus(), mala.toString());
        }
        verify(avisos, never()).save(any());
    }

    @Test
    void la_vuelta_la_pagina_y_la_consulta_bloquean_la_fila_del_pago_antes_de_mirarlo() {
        abiertoEnWebpay(TOKEN);
        when(webpay.estado(TOKEN)).thenReturn(transbank("INITIALIZED", null, null, 410000, "ORD41T123"));
        when(webpay.commitTransaction(TOKEN)).thenReturn(transbank("AUTHORIZED", 0, "TSY", 410000, "ORD41T123"));

        servicio.paginaWebpay(41L, firmaDe("41"));
        servicio.conciliarWebpay(41L);
        servicio.retornoWebpay(TOKEN, null, null, null);

        verify(payments, times(2)).paraCerrar(41L);
        verify(payments).paraCerrarPorToken(Payment.Gateway.webpay, TOKEN);
        verify(payments, never()).findByGatewayAndGatewayTxnId(any(), any());
    }

    @Test
    void la_consulta_y_la_vuelta_del_deudor_confirman_una_sola_vez() {
        Payment pago = abiertoEnWebpay(TOKEN);
        when(webpay.estado(TOKEN)).thenReturn(transbank("INITIALIZED", null, "TSY", 410000, "ORD41T123"));
        when(webpay.commitTransaction(TOKEN)).thenReturn(transbank("AUTHORIZED", 0, "TSY", 410000, "ORD41T123"));

        assertTrue(servicio.conciliarWebpay(41L));
        servicio.retornoWebpay(TOKEN, null, null, null);
        assertFalse(servicio.conciliarWebpay(41L), "ya cerrado, no se vuelve a mirar");

        assertEquals(Payment.Status.paid, pago.getStatus());
        verify(webpay, times(1)).commitTransaction(TOKEN);
        verify(avisos, times(1)).save(any(DebtNotification.class));
    }
}
