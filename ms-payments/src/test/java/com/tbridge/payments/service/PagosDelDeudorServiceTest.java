package com.tbridge.payments.service;

import com.tbridge.payments.dto.response.PagoDeDeudorResponse;
import com.tbridge.payments.exception.ApiException;
import com.tbridge.payments.model.Payment;
import com.tbridge.payments.repository.PaymentRepository;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Los pagos de un deudor en su ficha del panel (#434). */
class PagosDelDeudorServiceTest {

    private final PaymentRepository pagos = mock(PaymentRepository.class);
    private final PagosDelDeudorService servicio = new PagosDelDeudorService(pagos);

    @Test
    void un_rut_con_puntos_o_sin_guion_busca_el_mismo_deudor() {
        assertEquals("16482337-7", PagosDelDeudorService.normalizar("16.482.337-7"));
        assertEquals("16482337-K", PagosDelDeudorService.normalizar("16482337k"));
        servicio.pagos("16.482.337-7");
        verify(pagos).findTop100ByDebtorRutOrderByCreatedAtDesc("16482337-7");
    }

    @Test
    void un_rut_que_no_tiene_forma_de_rut_es_400_y_no_se_consulta() {
        ApiException error = assertThrows(ApiException.class, () -> servicio.pagos("hola"));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verify(pagos, never()).findTop100ByDebtorRutOrderByCreatedAtDesc(any());
    }

    @Test
    void cada_pago_dice_pasarela_estado_monto_y_cuantas_cuotas_cubre() {
        Payment pago = new Payment();
        pago.setId(2494L);
        pago.setDebtId(15L);
        pago.setDebtorRut("16482337-7");
        pago.setCreditorRut("76418902-7");
        pago.setGateway(Payment.Gateway.webpay);
        pago.setStatus(Payment.Status.paid);
        pago.setAmount(new BigDecimal("125000"));
        pago.setCurrency(Payment.Currency.CLP);
        pago.setAmountClp(125000L);
        pago.setCuotas(List.of(60L, 61L));
        pago.setCreatedAt(Instant.parse("2026-11-13T15:00:00Z"));
        pago.setPaidAt(Instant.parse("2026-11-13T15:02:00Z"));
        when(pagos.findTop100ByDebtorRutOrderByCreatedAtDesc("16482337-7")).thenReturn(List.of(pago));

        PagoDeDeudorResponse visto = servicio.pagos("16482337-7").getFirst();

        assertEquals(15L, visto.deuda());
        assertEquals(Payment.Gateway.webpay, visto.pasarela());
        assertEquals(Payment.Status.paid, visto.estado());
        assertEquals(2, visto.cuotas());
        assertEquals(125000L, visto.montoEnPesos());
    }
}
