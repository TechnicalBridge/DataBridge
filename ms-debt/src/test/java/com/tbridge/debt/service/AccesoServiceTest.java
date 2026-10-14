package com.tbridge.debt.service;

import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.security.JwtPrincipal;
import com.tbridge.debt.client.AuthClient;
import com.tbridge.debt.dto.response.CodigoEnviadoResponse;
import com.tbridge.debt.model.Contact;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.Debtor;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.ContactRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AccesoServiceTest {

    @Test
    void elCorreoSeReconoceSinExponerse() {
        assertEquals("fe**********@correo.cl", AccesoService.enmascarar("felipe.rojas@correo.cl"));
        assertEquals("***@x.cl", AccesoService.enmascarar("ab@x.cl"));
    }

    @Test
    void la_empresa_no_puede_reenviar_mas_de_lo_que_deja_la_ley() {
        DebtService debts = mock(DebtService.class);
        AuthClient auth = mock(AuthClient.class);
        LimiteDeContacto limite = mock(LimiteDeContacto.class);
        ContactRepository contactos = mock(ContactRepository.class);
        AccesoService servicio = new AccesoService(debts, contactos, auth, limite);
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
        verify(auth, never()).emitirCodigo(any());
        verify(contactos, never()).save(any());
    }

    @Test
    void el_codigo_que_reenvia_la_empresa_queda_como_un_contacto_suyo() {
        DebtService debts = mock(DebtService.class);
        AuthClient auth = mock(AuthClient.class);
        LimiteDeContacto limite = mock(LimiteDeContacto.class);
        ContactRepository contactos = mock(ContactRepository.class);
        AccesoService servicio = new AccesoService(debts, contactos, auth, limite);
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
        verify(contactos).save(contacto.capture());
        assertEquals(Contact.Kind.resend, contacto.getValue().getKind());
        assertTrue(contacto.getValue().deLaEmpresa());
        assertSame(deuda, contacto.getValue().getDebt());
        assertEquals("fe**********@correo.cl", contacto.getValue().getDestination());
    }
}
