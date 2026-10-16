package com.tbridge.debt.service;

import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.client.AuthClient;
import com.tbridge.debt.client.CorreoNoSalio;
import com.tbridge.debt.dto.response.RecordatoriosResponse;
import com.tbridge.debt.model.Contact;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.Debtor;
import com.tbridge.debt.model.Installment;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.ContactRepository;
import com.tbridge.debt.repository.InstallmentRepository;
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
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** El recordatorio sale una vez por cuota, y solo a quien lo quiere y tiene correo. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RecordatorioServiceTest {

    private static final LocalDate HOY = LocalDate.of(2026, 10, 17);

    @Mock private InstallmentRepository installments;
    @Mock private ContactRepository contactos;
    @Mock private AuthClient auth;
    @Mock private LimiteDeContacto limite;

    private RecordatorioService servicio;
    private Installment cuota;
    private Debtor deudor;

    @BeforeEach
    void preparar() {
        servicio = new RecordatorioService(installments, contactos, auth, limite, new HorarioDeContacto(""), 3);
        when(limite.permite(any(), any())).thenReturn(true);
        Organization patrimonio = new Organization();
        patrimonio.setTradeName("Patrimonio Inmuebles");
        deudor = new Debtor();
        deudor.setRut("16482337-7");
        deudor.setEmail("felipe.rojas@correo.cl");
        Debt deuda = new Debt();
        deuda.setCreditor(patrimonio);
        deuda.setDebtor(deudor);
        deuda.setExternalId("CTR-2025-014");
        cuota = new Installment();
        cuota.setId(34L);
        cuota.setDebt(deuda);
        cuota.setDueDate(LocalDate.of(2026, 10, 20));
        cuota.setAmount(new BigDecimal("173333"));
        when(installments.porRecordar(Installment.Status.pending, HOY, HOY.plusDays(3),
                List.of(Debt.Status.open, Debt.Status.repacted))).thenReturn(List.of(cuota));
    }

    @Test
    void la_cuota_por_vencer_se_recuerda_con_su_fecha_y_queda_su_contacto() {
        RecordatoriosResponse pasada = servicio.enviar(HOY);

        ArgumentCaptor<AuthClient.PedidoDeCodigo> pedido = ArgumentCaptor.forClass(AuthClient.PedidoDeCodigo.class);
        verify(auth).emitirCodigo(pedido.capture());
        assertEquals(LocalDate.of(2026, 10, 20), pedido.getValue().vence());
        assertEquals("felipe.rojas@correo.cl", pedido.getValue().correo());
        assertEquals("Patrimonio Inmuebles", pedido.getValue().acreedor());
        //  Que la cuota ya se aviso es este contacto: por el no vuelve a salir en porRecordar.
        ArgumentCaptor<Contact> contacto = ArgumentCaptor.forClass(Contact.class);
        verify(contactos).save(contacto.capture());
        assertEquals(Contact.Kind.reminder, contacto.getValue().getKind());
        assertSame(cuota, contacto.getValue().getInstallment());
        assertSame(cuota.getDebt(), contacto.getValue().getDebt());
        assertEquals("fe**********@correo.cl", contacto.getValue().getDestination());
        assertNull(contacto.getValue().getCampaign());
        assertEquals(1, pasada.enviados());
    }

    @Test
    void un_domingo_o_un_feriado_no_sale_ningun_recordatorio() {
        //  El domingo 18 y el lunes 12 de octubre de 2026 (Encuentro de Dos Mundos).
        for (LocalDate dia : List.of(LocalDate.of(2026, 10, 18), LocalDate.of(2026, 10, 12))) {
            RecordatoriosResponse pasada = servicio.enviar(dia);

            assertEquals(0, pasada.enviados() + pasada.omitidos(), dia.toString());
        }
        verify(installments, never()).porRecordar(any(), any(), any(), any());
        verify(auth, never()).emitirCodigo(any());
    }

    @Test
    void si_la_ley_no_deja_escribirle_hoy_el_recordatorio_sale_otro_dia() {
        when(limite.permite(any(), any())).thenReturn(false);

        assertEquals(1, servicio.enviar(HOY).omitidos());

        verify(auth, never()).emitirCodigo(any());
        //  Sin contacto: la proxima pasada lo intenta de nuevo.
        verify(contactos, never()).save(any());
    }

    @Test
    void quien_apago_los_recordatorios_o_no_tiene_correo_no_recibe_nada() {
        deudor.setReminders(false);
        assertEquals(1, servicio.enviar(HOY).omitidos());

        deudor.setReminders(true);
        deudor.setEmail(null);
        assertEquals(1, servicio.enviar(HOY).omitidos());

        verify(auth, never()).emitirCodigo(any());
        verify(contactos, never()).save(any());
    }

    @Test
    void si_el_servidor_rechaza_la_direccion_la_cuota_queda_sin_avisar_y_el_motivo_en_el_deudor() {
        when(auth.emitirCodigo(any())).thenThrow(new CorreoNoSalio("550 User unknown", true));

        RecordatoriosResponse pasada = servicio.enviar(HOY);

        assertEquals(0, pasada.enviados());
        assertEquals(1, pasada.omitidos());
        verify(contactos, never()).save(any());
        assertEquals(1, deudor.getEmailRejections());
    }

    @Test
    void si_falla_el_servidor_de_correo_la_pasada_se_corta() {
        Installment otra = new Installment();
        otra.setId(35L);
        otra.setDebt(cuota.getDebt());
        otra.setDueDate(LocalDate.of(2026, 10, 19));
        otra.setAmount(new BigDecimal("173333"));
        when(installments.porRecordar(Installment.Status.pending, HOY, HOY.plusDays(3),
                List.of(Debt.Status.open, Debt.Status.repacted))).thenReturn(List.of(cuota, otra));
        when(auth.emitirCodigo(any())).thenThrow(new CorreoNoSalio("Connect timed out", false));

        servicio.enviar(HOY);

        verify(auth, times(1)).emitirCodigo(any());
        assertEquals(0, deudor.getEmailRejections());
        assertTrue(deudor.getEmailFailure().contains("Connect timed out"));
    }

    @Test
    void a_un_correo_con_problemas_no_se_le_recuerda() {
        for (int i = 0; i < 3; i++) {
            deudor.correoNoSalio("rechazada", true, java.time.Instant.now());
        }

        assertEquals(1, servicio.enviar(HOY).omitidos());
        verify(auth, never()).emitirCodigo(any());
    }

    @Test
    void si_ms_auth_no_responde_la_cuota_queda_para_la_pasada_siguiente() {
        when(auth.emitirCodigo(any())).thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "sin ms-auth"));

        RecordatoriosResponse pasada = servicio.enviar(HOY);

        assertEquals(0, pasada.enviados());
        assertEquals(1, pasada.omitidos());
        verify(contactos, never()).save(any());
    }
}
