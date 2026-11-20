package com.tbridge.debt.service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.sql.Timestamp;
@Component
public class ContactChallengeCleanup {
    private final JdbcTemplate jdbc;
    public ContactChallengeCleanup(JdbcTemplate jdbc) { this.jdbc=jdbc; }
    @Scheduled(fixedDelayString="${app.contact-cleanup.interval-ms:3600000}")
    @Transactional
    public void limpiar() {
        jdbc.update("DELETE FROM contact_rate_limits WHERE window_id < ?", Instant.now().getEpochSecond()/900-96);
        jdbc.update("DELETE FROM email_changes WHERE expires_at < ?", Timestamp.from(Instant.now().minusSeconds(86400)));
        // Keep failed notices available for operations; successful notices hold no address.
    }
}
