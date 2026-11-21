package com.tbridge.debt.service;
import com.tbridge.debt.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import java.time.Instant;

/** Shared MySQL counters. Caller holds a transaction; no in-memory limits. */
@Component
public class ContactoRateLimit {
    private final JdbcTemplate jdbc;
    public ContactoRateLimit(JdbcTemplate jdbc) { this.jdbc = jdbc; }
    public void tomar(String key, int max) {
        long window = Instant.now().getEpochSecond() / 900;
        jdbc.update("INSERT INTO contact_rate_limits (bucket_key, window_id, attempts) VALUES (?, ?, 0) ON DUPLICATE KEY UPDATE bucket_key = bucket_key", key, window);
        Integer count = jdbc.queryForObject("SELECT attempts FROM contact_rate_limits WHERE bucket_key = ? AND window_id = ? FOR UPDATE", Integer.class, key, window);
        if (count == null || count >= max)
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Demasiadas solicitudes. Espera quince minutos.");
        jdbc.update("UPDATE contact_rate_limits SET attempts = attempts + 1 WHERE bucket_key = ? AND window_id = ?", key, window);
    }
}
