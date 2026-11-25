package com.tbridge.debt.service;
import com.tbridge.debt.client.AuthClient;
import com.tbridge.debt.repository.ContactNoticeRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

@Service
public class ContactNoticeDispatcher {
    private final ContactNoticeRepository avisos;
    private final AuthClient acceso;
    public ContactNoticeDispatcher(ContactNoticeRepository avisos, AuthClient acceso) {
        this.avisos = avisos; this.acceso = acceso;
    }
    @Scheduled(fixedDelayString = "${app.contact-notices.interval-ms:30000}")
    @Transactional
    public void enviar() {
        for (var aviso : avisos.findTop20ByDeliveredAtIsNullAndAttemptsLessThanAndNextAttemptAtBeforeOrderByNextAttemptAtAsc(7, Instant.now())) {
            try {
                acceso.avisarCambio(aviso.id, aviso.oldEmail);
                aviso.deliveredAt = Instant.now();
                aviso.oldEmail = null;
            } catch (RuntimeException fallo) {
                aviso.attempts++;
                aviso.nextAttemptAt = Instant.now().plusSeconds(60L * (1L << aviso.attempts));
                // No guardar excepciones ni datos de contacto en registros o errores persistidos.
            }
        }
    }
}
