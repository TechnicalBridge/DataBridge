package com.tbridge.debt.service;

import com.tbridge.debt.client.AuthClient;
import com.tbridge.debt.client.CorreoNoSalio;
import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.DebtEvent;
import com.tbridge.debt.model.Debtor;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.DebtEventRepository;
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** El comprobante de un pago, por correo al deudor apenas se aplica (#541). */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ComprobantePorCorreoTest {

    private static final byte[] COMPROBANTE = "%PDF-1.4 comprobante".getBytes();
    private static final byte[] CERTIFICADO = "%PDF-1.4 certificado".getBytes();

    @Mock private DebtEventRepository eventos;
    @Mock private DebtService deudas;
    @Mock private ComprobanteService comprobantes;
    @Mock private CertificateService certificados;
    @Mock private AuthClient auth;

    private ComprobantePorCorreo servicio;
    private Debt deuda;
    private Debtor deudor;
    private DebtEvent pago;

    @BeforeEach
    void preparar() {
        servicio = new ComprobantePorCorreo(eventos, deudas, comprobantes, certificados, auth);
        Organization patrimonio = new Organization();
        patrimonio.setTradeName("Patrimonio Inmuebles");
        deudor = new Debtor();
        deudor.setRut("16482337-7");
        deudor.setEmail("felipe.rojas@correo.cl");
        deuda = new Debt();
        deuda.setId(8L);
        deuda.setCreditor(patrimonio);
        deuda.setDebtor(deudor);
        deuda.setCurrency(Debt.Currency.CLP);
        deuda.setStatus(Debt.Status.repacted);
        pago = DebtEvent.de(deuda, DebtEvent.Type.payment_applied, DebtEvent.Actor.system);
        when(eventos.findById(57L)).thenReturn(Optional.of(pago));

        ComprobanteService.Comprobante comprobante = new ComprobanteService.Comprobante(57L, "$346.666", null,
                "Patrimonio Inmuebles", "Felipe Rojas, RUT 16.482.337-7", List.of(
                        new String[]{"Qué se pagó", "Cuota 3 de 6"},
                        new String[]{"Medio de pago", "Webpay, operación wp-9f31c2"}));
        when(comprobantes.armar(pago)).thenReturn(comprobante);
        when(comprobantes.pdf(comprobante)).thenReturn(COMPROBANTE);
        when(certificados.de(deuda)).thenReturn(CERTIFICADO);
        when(deudas.saldo(deuda)).thenReturn(new BigDecimal("693332"));
    }

    private AuthClient.PedidoDeComprobante enviado() {
        ArgumentCaptor<AuthClient.PedidoDeComprobante> pedido = ArgumentCaptor.forClass(AuthClient.PedidoDeComprobante.class);
        verify(auth).enviarComprobante(pedido.capture());
        return pedido.getValue();
    }

    private void enviar() {
        servicio.enviar(new ComprobantePorCorreo.PagoAplicado(57L));
    }

    @Test
    void una_cuota_pagada_manda_el_detalle_lo_que_queda_y_el_comprobante() {
        enviar();

        AuthClient.PedidoDeComprobante pedido = enviado();
        assertEquals("felipe.rojas@correo.cl", pedido.correo());
        assertEquals("57", pedido.folio());
        assertEquals("$346.666", pedido.monto());
        assertEquals("Patrimonio Inmuebles", pedido.acreedor());
        assertEquals(List.of(new AuthClient.Fila("Qué se pagó", "Cuota 3 de 6"),
                new AuthClient.Fila("Medio de pago", "Webpay, operación wp-9f31c2")), pedido.filas());
        //  Quien pago no se repite: el correo le llega a el. El PDF si lo dice.
        assertFalse(pedido.filas().stream().anyMatch(f -> f.nombre().equals("Pagado por")));
        assertFalse(pedido.saldada());
        assertEquals("$693.332", pedido.queda());
        assertEquals(1, pedido.adjuntos().size());
        assertEquals("comprobante-57.pdf", pedido.adjuntos().getFirst().nombre());
        assertArrayEquals(COMPROBANTE, pedido.adjuntos().getFirst().contenido());
        verify(certificados, never()).de(any());
    }

    @Test
    void si_el_pago_dejo_la_deuda_pagada_adjunta_tambien_el_certificado() {
        deuda.setStatus(Debt.Status.paid);
        when(deudas.saldo(deuda)).thenReturn(BigDecimal.ZERO);

        enviar();

        AuthClient.PedidoDeComprobante pedido = enviado();
        assertTrue(pedido.saldada());
        assertNull(pedido.queda());
        assertEquals(List.of("comprobante-57.pdf", "certificado-8.pdf"),
                pedido.adjuntos().stream().map(AuthClient.Adjunto::nombre).toList());
        assertArrayEquals(CERTIFICADO, pedido.adjuntos().get(1).contenido());
    }

    @Test
    void si_el_certificado_no_se_puede_hacer_el_comprobante_sale_igual() {
        deuda.setStatus(Debt.Status.paid);
        when(certificados.de(deuda)).thenThrow(new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo"));

        enviar();

        AuthClient.PedidoDeComprobante pedido = enviado();
        assertTrue(pedido.saldada());
        assertEquals(List.of("comprobante-57.pdf"), pedido.adjuntos().stream().map(AuthClient.Adjunto::nombre).toList());
    }

    @Test
    void sin_correo_no_se_manda() {
        deudor.setEmail(" ");

        enviar();

        verify(auth, never()).enviarComprobante(any());
    }

    @Test
    void con_la_direccion_rechazada_una_y_otra_vez_no_se_manda() {
        for (int i = 0; i < Debtor.RECHAZOS_PARA_DEJAR; i++) {
            deudor.correoNoSalio("550 no existe", true, Instant.now());
        }

        enviar();

        verify(auth, never()).enviarComprobante(any());
    }

    @Test
    void un_evento_que_no_es_un_pago_no_manda_nada() {
        when(eventos.findById(58L)).thenReturn(Optional.of(
                DebtEvent.de(deuda, DebtEvent.Type.settled, DebtEvent.Actor.system)));

        servicio.enviar(new ComprobantePorCorreo.PagoAplicado(58L));
        servicio.enviar(new ComprobantePorCorreo.PagoAplicado(59L));

        verify(auth, never()).enviarComprobante(any());
    }

    @Test
    void si_la_direccion_no_recibe_queda_anotado_en_el_deudor() {
        doThrow(new CorreoNoSalio("550 5.1.1 The email account that you tried to reach does not exist", true))
                .when(auth).enviarComprobante(any());

        assertDoesNotThrow(this::enviar);

        assertEquals(1, deudor.getEmailRejections());
        assertTrue(deudor.getEmailFailure().contains("550 5.1.1"), deudor.getEmailFailure());
    }

    @Test
    void si_ms_auth_no_responde_el_pago_ya_quedo_y_no_falla_nada() {
        doThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "no responde")).when(auth).enviarComprobante(any());

        assertDoesNotThrow(this::enviar);

        assertEquals(0, deudor.getEmailRejections());
    }

    @Test
    void si_salio_se_limpia_un_fallo_anterior() {
        deudor.correoNoSalio("451 temporal", false, Instant.now());

        enviar();

        assertNull(deudor.getEmailFailure());
    }
}
