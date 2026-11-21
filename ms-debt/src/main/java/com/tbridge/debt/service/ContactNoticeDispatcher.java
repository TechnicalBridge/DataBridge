package com.tbridge.debt.service;
import com.tbridge.debt.client.AuthClient;
import com.tbridge.debt.repository.ContactNoticeRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

@Service
public class ContactNoticeDispatcher {
    private final ContactNoticeRepository notices;
    private final AuthClient auth;
    public ContactNoticeDispatcher(ContactNoticeRepository notices, AuthClient auth) {
        this.notices = notices; this.auth = auth;
    }
    @Scheduled(fixedDelayString = "${app.contact-notices.interval-ms:30000}")
    @Transactional
    public void enviar() {
        for (var notice : notices.findTop20ByDeliveredAtIsNullAndAttemptsLessThanAndNextAttemptAtBeforeOrderByNextAttemptAtAsc(7, Instant.now())) {
            try {
                auth.avisarCambio(notice.id, notice.oldEmail);
                notice.deliveredAt = Instant.now();
                notice.oldEmail = null;
            } catch (RuntimeException failed) {
                notice.attempts++;
                notice.nextAttemptAt = Instant.now().plusSeconds(60L * (1L << notice.attempts));
                // No raw exceptions or contact data in logs or persisted errors.
            }
        }
    }
}
