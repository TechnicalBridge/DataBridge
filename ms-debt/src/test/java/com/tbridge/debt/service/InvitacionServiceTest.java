package com.tbridge.debt.service;

import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.client.AuthClient;
import com.tbridge.debt.model.Contact;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.Debtor;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.ContactRepository;
import com.tbridge.debt.repository.DebtRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Al deudor le llega su codigo apenas su deuda entra en cobranza, sin que nadie lo pida. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class InvitacionServiceTest {

    @Mock private DebtRepository debts;
    @Mock private ContactRepository contactos;
    @Mock private AuthClient auth;
    @Mock private LimiteDeContacto limite;

    private InvitacionService servicio;
    private Debt deuda;
    private Debtor deudor;

    @BeforeEach
    void preparar() {
        servicio = new InvitacionService(debts, contactos, auth, limite);
        when(limite.permite(any(), any())).thenReturn(true);

        Organization patrimonio = new Organization();
        patrimonio.setTradeName("Patrimonio Inmuebles");
        deudor = new Debtor();
        deudor.setRut("19230418-0");
        deudor.setEmail("carolina.munoz@correo.cl");
        deuda = new Debt();
        deuda.setId(7L);
        deuda.setCreditor(patrimonio);
        deuda.setDebtor(deudor);
        deuda.setExternalId("CTR-2026-012");
        deuda.setStatus(Debt.Status.open);
        when(debts.findById(7L)).thenReturn(Optional.of(deuda));
    }

    private void invitar() {
        servicio.invitar(new InvitacionService.DeudaEnCobranza(7L));
    }

    @Test
    void si_esta_semana_ya_se_le_escribio_lo_que_deja_la_ley_la_invitacion_espera() {
        when(limite.permite(any(), any())).thenReturn(false);

        invitar();

        verify(auth, never()).emitirCodigo(any());
        verify(contactos, never()).save(any());
    }

    @Test
    void le_envia_el_codigo_al_correo_y_lo_anota_como_una_invitacion() {
        invitar();

        ArgumentCaptor<AuthClient.PedidoDeCodigo> pedido = ArgumentCaptor.forClass(AuthClient.PedidoDeCodigo.class);
        verify(auth).emitirCodigo(pedido.capture());
        assertEquals("19230418-0", pedido.getValue().rut());
        assertEquals(List.of("correo"), pedido.getValue().canales());
        assertEquals("Patrimonio Inmuebles", pedido.getValue().acreedor());
        //  Sin fecha: es el primer aviso, no el recordatorio de una cuota.
        assertNull(pedido.getValue().vence());

        ArgumentCaptor<Contact> contacto = ArgumentCaptor.forClass(Contact.class);
        verify(contactos).save(contacto.capture());
        assertEquals(Contact.Kind.invitation, contacto.getValue().getKind());
        assertSame(deuda, contacto.getValue().getDebt());
        assertFalse(contacto.getValue().deLaEmpresa(), "lo manda el sistema");
        assertEquals("ca************@correo.cl", contacto.getValue().getDestination());
        assertNull(contacto.getValue().getCampaign());
        assertNull(contacto.getValue().getInstallment());
    }

    @Test
    void sin_correo_no_hay_por_donde_invitarlo() {
        deudor.setEmail(null);

        invitar();

        verify(auth, never()).emitirCodigo(any());
        verify(contactos, never()).save(any());
    }

    @Test
    void si_ms_auth_no_responde_la_deuda_queda_igual_y_no_se_anota() {
        when(auth.emitirCodigo(any())).thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "caido"));

        assertDoesNotThrow(this::invitar);
        verify(contactos, never()).save(any());
    }

    @Test
    void una_deuda_que_ya_no_esta_abierta_no_se_invita() {
        deuda.setStatus(Debt.Status.withdrawn);

        invitar();

        verify(auth, never()).emitirCodigo(any());
    }
}
