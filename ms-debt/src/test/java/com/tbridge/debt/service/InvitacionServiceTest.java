package com.tbridge.debt.service;

import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.client.AuthClient;
import com.tbridge.debt.client.CorreoNoSalio;
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

import java.time.Instant;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
    @Mock private HorarioDeContacto horario;

    private InvitacionService servicio;
    private Debt deuda;
    private Debtor deudor;

    @BeforeEach
    void preparar() {
        //  Sin tope: las pruebas de siempre no lo miran. El tope tiene las suyas abajo.
        servicio = new InvitacionService(debts, contactos, auth, limite, horario, new TopeDeInvitaciones(0));
        when(limite.permite(any(), any())).thenReturn(true);
        when(horario.permiteAhora()).thenReturn(true);

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
    void una_cartera_que_llega_de_noche_o_un_domingo_no_invita_hasta_el_horario_de_la_ley() {
        when(horario.permiteAhora()).thenReturn(false);

        invitar();

        //  Queda sin invitar: la primera pasada de contacto dentro del horario
        //  la invita (invitarPendientes, que solo corre en horario).
        verify(auth, never()).emitirCodigo(any());
        verify(contactos, never()).save(any());
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

    // ------------------------------------------------------------------
    //  Cuando el correo no sale
    // ------------------------------------------------------------------

    @Test
    void si_el_correo_no_sale_no_queda_como_enviado_y_el_motivo_queda_en_el_deudor() {
        when(auth.emitirCodigo(any())).thenThrow(new CorreoNoSalio("535 Username and Password not accepted", false));

        invitar();

        verify(contactos, never()).save(any());
        assertTrue(deudor.getEmailFailure().contains("535 Username and Password not accepted"));
        assertEquals(0, deudor.getEmailRejections());
    }

    @Test
    void a_un_correo_con_problemas_no_se_lo_invita() {
        for (int i = 0; i < 3; i++) {
            deudor.correoNoSalio("rechazada", true, Instant.now());
        }

        invitar();

        verify(auth, never()).emitirCodigo(any());
    }

    private static final Instant PASADA = Instant.parse("2026-10-14T13:00:00Z");

    private Set<Debtor> nadie() {
        return Collections.newSetFromMap(new IdentityHashMap<>());
    }

    @Test
    void la_invitacion_que_no_salio_sale_en_la_pasada_y_el_deudor_vuelve_a_cero() {
        deudor.correoNoSalio("el servidor de correo de DataBridge no lo acepto", false, PASADA.minusSeconds(3600));
        when(debts.sinInvitar(3)).thenReturn(List.of(deuda));

        InvitacionService.Pasada reintento = servicio.invitarPendientes(PASADA, nadie());

        assertEquals(1, reintento.enviadas());
        assertEquals(false, reintento.cortada());
        ArgumentCaptor<Contact> contacto = ArgumentCaptor.forClass(Contact.class);
        verify(contactos).save(contacto.capture());
        assertEquals(Contact.Kind.invitation, contacto.getValue().getKind());
        assertEquals(PASADA, contacto.getValue().getOccurredAt(), "fechada a la hora de la pasada");
        assertNull(deudor.getEmailFailedAt());
    }

    @Test
    void si_el_servidor_sigue_sin_aceptar_el_reintento_se_corta() {
        Debt otra = new Debt();
        otra.setId(8L);
        otra.setCreditor(deuda.getCreditor());
        otra.setDebtor(new Debtor());
        otra.getDebtor().setEmail("tomas.vidal@correo.cl");
        otra.setStatus(Debt.Status.open);
        when(debts.sinInvitar(3)).thenReturn(List.of(deuda, otra));
        when(auth.emitirCodigo(any())).thenThrow(new CorreoNoSalio("Connect timed out", false));

        InvitacionService.Pasada reintento = servicio.invitarPendientes(PASADA, nadie());

        assertTrue(reintento.cortada());
        verify(auth, times(1)).emitirCodigo(any());
    }

    @Test
    void si_rechaza_una_direccion_sigue_con_las_demas_y_ese_deudor_no_se_intenta_de_nuevo_en_la_pasada() {
        Debtor otro = new Debtor();
        otro.setRut("18905214-6");
        otro.setEmail("valentina.soto@correo.cl");
        Debt otra = new Debt();
        otra.setId(8L);
        otra.setCreditor(deuda.getCreditor());
        otra.setDebtor(otro);
        otra.setStatus(Debt.Status.open);
        when(debts.sinInvitar(3)).thenReturn(List.of(deuda, otra));
        when(auth.emitirCodigo(any()))
                .thenThrow(new CorreoNoSalio("550 User unknown", true))
                .thenReturn(new AuthClient.CodigoEmitido(PASADA));
        Set<Debtor> fallaron = nadie();

        InvitacionService.Pasada reintento = servicio.invitarPendientes(PASADA, fallaron);

        assertEquals(1, reintento.enviadas());
        assertTrue(fallaron.contains(deudor), "la pasada de campanas ya no le intenta");
        assertEquals(1, deudor.getEmailRejections());
    }

    @Test
    void el_reintento_respeta_el_limite_de_la_semana() {
        when(debts.sinInvitar(3)).thenReturn(List.of(deuda));
        when(limite.permite(any(), any())).thenReturn(false);

        assertEquals(0, servicio.invitarPendientes(PASADA, nadie()).enviadas());
        verify(auth, never()).emitirCodigo(any());
    }

    @Test
    void si_ms_auth_no_responde_el_reintento_queda_para_la_pasada_siguiente() {
        when(debts.sinInvitar(3)).thenReturn(List.of(deuda));
        when(auth.emitirCodigo(any())).thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "caido"));

        assertTrue(servicio.invitarPendientes(PASADA, nadie()).cortada());
        verify(contactos, never()).save(any());
    }

    @Test
    void una_deuda_que_ya_no_esta_abierta_no_se_invita() {
        deuda.setStatus(Debt.Status.withdrawn);

        invitar();

        verify(auth, never()).emitirCodigo(any());
    }
    // ------------------------------------------------------------------
    //  El tope por cuarto de hora (#236)
    // ------------------------------------------------------------------

    private Debt otraDeuda(long id, String correo) {
        Debt otra = new Debt();
        otra.setId(id);
        otra.setCreditor(deuda.getCreditor());
        otra.setDebtor(new Debtor());
        otra.getDebtor().setRut("1000000" + id + "-0");
        otra.getDebtor().setEmail(correo);
        otra.setExternalId("CTR-" + id);
        otra.setStatus(Debt.Status.open);
        when(debts.findById(id)).thenReturn(Optional.of(otra));
        return otra;
    }

    @Test
    void una_carga_grande_no_invita_a_todos_en_el_mismo_cuarto_de_hora() {
        servicio = new InvitacionService(debts, contactos, auth, limite, horario, new TopeDeInvitaciones(2));
        otraDeuda(8L, "tomas.vidal@example.com");
        otraDeuda(9L, "sofia.lagos@example.com");

        servicio.invitar(new InvitacionService.DeudaEnCobranza(7L));
        servicio.invitar(new InvitacionService.DeudaEnCobranza(8L));
        servicio.invitar(new InvitacionService.DeudaEnCobranza(9L));

        verify(auth, times(2)).emitirCodigo(any());
        verify(contactos, times(2)).save(any());
    }

    @Test
    void la_pasada_invita_hasta_el_tope_y_deja_el_resto_sin_cortar_las_campanas() {
        servicio = new InvitacionService(debts, contactos, auth, limite, horario, new TopeDeInvitaciones(2));
        Debt segunda = otraDeuda(8L, "tomas.vidal@example.com");
        Debt tercera = otraDeuda(9L, "sofia.lagos@example.com");
        when(debts.sinInvitar(3)).thenReturn(List.of(deuda, segunda, tercera));

        InvitacionService.Pasada pasada = servicio.invitarPendientes(PASADA, nadie());

        assertEquals(2, pasada.enviadas());
        assertFalse(pasada.cortada(), "las campanas siguen: el tope no es una falla del correo");
        verify(auth, times(2)).emitirCodigo(any());
    }

    @Test
    void la_deuda_de_un_acreedor_suspendido_no_se_invita_al_llegar_y_queda_para_la_pasada() {
        deuda.getCreditor().setStatus(Organization.Status.suspended);

        invitar();

        //  Queda sin ningun contacto: invitarPendientes la invita cuando la reactiven.
        verify(auth, never()).emitirCodigo(any());
        verify(contactos, never()).save(any());

        deuda.getCreditor().setStatus(Organization.Status.active);
        invitar();
        verify(auth).emitirCodigo(any());
    }
}
