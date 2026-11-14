package com.tbridge.debt.service;

import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.security.JwtPrincipal;
import com.tbridge.debt.client.AuthClient;
import com.tbridge.debt.client.CorreoNoSalio;
import com.tbridge.debt.dto.response.CodigoEnviadoResponse;
import com.tbridge.debt.model.Campaign;
import com.tbridge.debt.model.Contact;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.Debtor;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.ContactRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccesoServiceTest {

    /** Dentro del horario de la ley, que es lo que prueba casi todo esto. */
    private static HorarioDeContacto enHorario() {
        HorarioDeContacto horario = mock(HorarioDeContacto.class);
        when(horario.permiteAhora()).thenReturn(true);
        return horario;
    }

    @Test
    void elCorreoSeReconoceSinExponerse() {
        assertEquals("fe**********@correo.cl", AccesoService.enmascarar("felipe.rojas@correo.cl"));
        assertEquals("***@x.cl", AccesoService.enmascarar("ab@x.cl"));
    }

    @Test
    void la_empresa_no_puede_reenviar_el_codigo_fuera_del_horario_de_la_ley() {
        DebtService debts = mock(DebtService.class);
        ContactOutbox auth = mock(ContactOutbox.class);
        when(auth.enqueue(any())).thenReturn(true);
        LimiteDeContacto limite = mock(LimiteDeContacto.class);
        ContactRepository contactos = mock(ContactRepository.class);
        HorarioDeContacto deNoche = mock(HorarioDeContacto.class);
        AccesoService servicio = new AccesoService(debts, auth, limite, deNoche);
        JwtPrincipal empresa = new JwtPrincipal("1", "camila.reyes@apofyx.cl", "CREDITOR", "Camila", "77305118-6");
        Debtor deudor = new Debtor();
        deudor.setEmail("felipe.rojas@correo.cl");
        Debt deuda = new Debt();
        deuda.setDebtor(deudor);
        deuda.setStatus(Debt.Status.open);
        when(debts.requireVisible(empresa, 3L)).thenReturn(deuda);
        when(limite.permite(any(), any())).thenReturn(true);
        when(deNoche.permiteAhora()).thenReturn(false);

        ApiException error = assertThrows(ApiException.class, () -> servicio.enviarCodigo(empresa, 3L));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertTrue(error.getMessage().contains("de lunes a sabado, de 8:00 a 20:00"), error.getMessage());
        verify(auth, never()).enqueue(any());
        verify(contactos, never()).save(any());
    }

    @Test
    void la_empresa_no_puede_reenviar_mas_de_lo_que_deja_la_ley() {
        DebtService debts = mock(DebtService.class);
        ContactOutbox auth = mock(ContactOutbox.class);
        when(auth.enqueue(any())).thenReturn(true);
        LimiteDeContacto limite = mock(LimiteDeContacto.class);
        ContactRepository contactos = mock(ContactRepository.class);
        AccesoService servicio = new AccesoService(debts, auth, limite, enHorario());
        JwtPrincipal empresa = new JwtPrincipal("1", "camila.reyes@apofyx.cl", "CREDITOR", "Camila", "77305118-6");
        Debtor deudor = new Debtor();
        deudor.setEmail("felipe.rojas@correo.cl");
        Debt deuda = new Debt();
        deuda.setDebtor(deudor);
        deuda.setStatus(Debt.Status.open);
        when(debts.requireVisible(empresa, 3L)).thenReturn(deuda);
        when(limite.permite(any(), any())).thenReturn(false);

        ApiException error = assertThrows(ApiException.class, () -> servicio.enviarCodigo(empresa, 3L));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertTrue(error.getMessage().startsWith("Ya se le escribio lo que permite la ley"), error.getMessage());
        verify(auth, never()).enqueue(any());
        verify(contactos, never()).save(any());
    }



    @Test
    void la_empresa_puede_insistir_aunque_la_direccion_tenga_problemas_y_si_sale_vuelve_a_cero() {
        DebtService debts = mock(DebtService.class);
        ContactOutbox auth = mock(ContactOutbox.class);
        when(auth.enqueue(any())).thenReturn(true);
        LimiteDeContacto limite = mock(LimiteDeContacto.class);
        AccesoService servicio = new AccesoService(debts, auth, limite, enHorario());
        JwtPrincipal empresa = new JwtPrincipal("1", "camila.reyes@apofyx.cl", "CREDITOR", "Camila", "77305118-6");
        Organization patrimonio = new Organization();
        patrimonio.setTradeName("Patrimonio Inmuebles");
        Debtor deudor = new Debtor();
        deudor.setEmail("felipe.rojas@correo.cl");
        for (int i = 0; i < 3; i++) {
            deudor.correoNoSalio("rechazada", true, java.time.Instant.now());
        }
        Debt deuda = new Debt();
        deuda.setDebtor(deudor);
        deuda.setCreditor(patrimonio);
        deuda.setStatus(Debt.Status.open);
        when(debts.requireVisible(empresa, 3L)).thenReturn(deuda);
        when(limite.permite(any(), any())).thenReturn(true);

        servicio.enviarCodigo(empresa, 3L);

        assertEquals(true, deudor.correoConProblemas(), "encolar no confirma aceptacion");
        org.junit.jupiter.api.Assertions.assertNotNull(deudor.getEmailFailedAt());
    }

    @Test
    void el_codigo_que_reenvia_la_empresa_queda_como_un_contacto_suyo() {
        DebtService debts = mock(DebtService.class);
        ContactOutbox auth = mock(ContactOutbox.class);
        when(auth.enqueue(any())).thenReturn(true);
        LimiteDeContacto limite = mock(LimiteDeContacto.class);
        ContactRepository contactos = mock(ContactRepository.class);
        AccesoService servicio = new AccesoService(debts, auth, limite, enHorario());
        JwtPrincipal empresa = new JwtPrincipal("1", "camila.reyes@apofyx.cl", "CREDITOR", "Camila", "77305118-6");
        Organization patrimonio = new Organization();
        patrimonio.setTradeName("Patrimonio Inmuebles");
        Debtor deudor = new Debtor();
        deudor.setRut("16482337-7");
        deudor.setEmail("felipe.rojas@correo.cl");
        Debt deuda = new Debt();
        deuda.setDebtor(deudor);
        deuda.setCreditor(patrimonio);
        deuda.setStatus(Debt.Status.open);
        when(debts.requireVisible(empresa, 3L)).thenReturn(deuda);
        when(limite.permite(any(), any())).thenReturn(true);

        CodigoEnviadoResponse respuesta = servicio.enviarCodigo(empresa, 3L);

        assertEquals("fe**********@correo.cl", respuesta.destino());
        ArgumentCaptor<Contact> contacto = ArgumentCaptor.forClass(Contact.class);
        verify(auth).enqueue(contacto.capture());
        verify(contactos, never()).save(any());
        assertEquals(Contact.Kind.resend, contacto.getValue().getKind());
        assertTrue(contacto.getValue().deLaEmpresa());
        assertSame(deuda, contacto.getValue().getDebt());
        assertEquals("fe**********@correo.cl", contacto.getValue().getDestination());
    }

    @Test
    void si_la_agencia_que_la_cobra_esta_suspendida_el_acreedor_no_puede_reenviarle_el_codigo() {
        DebtService debts = mock(DebtService.class);
        ContactOutbox auth = mock(ContactOutbox.class);
        when(auth.enqueue(any())).thenReturn(true);
        LimiteDeContacto limite = mock(LimiteDeContacto.class);
        ContactRepository contactos = mock(ContactRepository.class);
        AccesoService servicio = new AccesoService(debts, auth, limite, enHorario());
        JwtPrincipal acreedor = new JwtPrincipal("2", "ana@patrimonio.cl", "CREDITOR", "Ana", "76418902-7");
        Organization patrimonio = new Organization();
        Organization apofyx = new Organization();
        apofyx.setStatus(Organization.Status.suspended);
        Campaign campana = new Campaign();
        campana.setAgency(apofyx);
        Debtor deudor = new Debtor();
        deudor.setEmail("felipe.rojas@correo.cl");
        Debt deuda = new Debt();
        deuda.setDebtor(deudor);
        deuda.setCreditor(patrimonio);
        deuda.setCampaign(campana);
        deuda.setStatus(Debt.Status.open);
        when(debts.requireVisible(acreedor, 3L)).thenReturn(deuda);
        when(limite.permite(any(), any())).thenReturn(true);

        ApiException error = assertThrows(ApiException.class, () -> servicio.enviarCodigo(acreedor, 3L));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertEquals("La empresa que cobra esta deuda está suspendida: por ahora no se le escribe al deudor",
                error.getMessage());
        verify(auth, never()).enqueue(any());
        verify(contactos, never()).save(any());

        apofyx.setStatus(Organization.Status.active);
        servicio.enviarCodigo(acreedor, 3L);
        verify(auth).enqueue(any());
    }
}
