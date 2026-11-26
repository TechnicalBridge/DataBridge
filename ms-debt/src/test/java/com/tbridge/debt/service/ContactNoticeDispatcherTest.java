package com.tbridge.debt.service;

import com.tbridge.debt.client.AuthClient;
import com.tbridge.debt.model.ContactNotice;
import com.tbridge.debt.repository.ContactNoticeRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.*;

class ContactNoticeDispatcherTest {
    private final ContactNoticeRepository avisos = mock(ContactNoticeRepository.class);
    private final AuthClient auth = mock(AuthClient.class);

    @Test void entregaCodigoAlCorreoAnteriorYLoBorraDelAviso() {
        ContactNotice aviso = new ContactNotice();
        aviso.id = "c3a62c70-5c77-4ff7-8986-aadd50f6d8f1";
        aviso.oldEmail = "anterior@example.com";
        aviso.revertCode = "2345678A";
        aviso.nextAttemptAt = Instant.now().minusSeconds(1);
        when(avisos.findTop20ByDeliveredAtIsNullAndAttemptsLessThanAndNextAttemptAtBeforeOrderByNextAttemptAtAsc(eq(7), any(Instant.class)))
                .thenReturn(List.of(aviso));

        new ContactNoticeDispatcher(avisos, auth).enviar();

        verify(auth).avisarCambio(aviso.id, "anterior@example.com", "2345678A");
        assertNull(aviso.oldEmail);
        assertNull(aviso.revertCode);
        org.junit.jupiter.api.Assertions.assertNotNull(aviso.deliveredAt);
    }
}
