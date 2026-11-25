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
    public void enviar(String eventoId, String correo, String codigo) {
        Boolean reclamado = tx.execute(estadoTransaccion -> jdbc.update(
                "INSERT IGNORE INTO contact_notice_receipts (event_id, state) VALUES (?, 'SENDING')", eventoId) == 1);
        if (!Boolean.TRUE.equals(reclamado)) {
            String estado = jdbc.queryForObject("SELECT state FROM contact_notice_receipts WHERE event_id = ?", String.class, eventoId);
            if ("SENT".equals(estado)) return;
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Aviso pendiente de revisión de entrega");
        }
        // No reintentar una aceptacion SMTP ambigua: podria duplicar el aviso.
        mail.enviarAvisoDeCambio(correo, codigo);
        tx.executeWithoutResult(estadoTransaccion -> jdbc.update("UPDATE contact_notice_receipts SET state = 'SENT' WHERE event_id = ?", eventoId));
    }
}
