package com.tbridge.auth.service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import com.tbridge.auth.exception.ApiException;
import org.springframework.http.HttpStatus;

@Service
public class ContactNoticeService {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final MailService mail;
    public ContactNoticeService(JdbcTemplate jdbc, PlatformTransactionManager manager, MailService mail) {
        this.jdbc=jdbc; this.tx=new TransactionTemplate(manager); this.mail=mail;
    }
    public void enviar(String event, String email) {
        Boolean claimed = tx.execute(status -> jdbc.update(
                "INSERT IGNORE INTO contact_notice_receipts (event_id, state) VALUES (?, 'SENDING')", event) == 1);
        if (!Boolean.TRUE.equals(claimed)) {
            String state = jdbc.queryForObject("SELECT state FROM contact_notice_receipts WHERE event_id = ?", String.class, event);
            if ("SENT".equals(state)) return;
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Aviso pendiente de revision de entrega");
        }
        // Do not retry an ambiguous SMTP acceptance: that could duplicate the notice.
        mail.enviarAvisoDeCambio(email);
        tx.executeWithoutResult(status -> jdbc.update("UPDATE contact_notice_receipts SET state = 'SENT' WHERE event_id = ?", event));
    }
}
