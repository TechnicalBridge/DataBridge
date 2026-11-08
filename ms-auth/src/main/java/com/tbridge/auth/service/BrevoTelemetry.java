package com.tbridge.auth.service;

import com.tbridge.auth.util.Hash;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Solo identificadores de mensaje y tiempos; nunca el payload, destinatario ni asunto. */
@Service
public class BrevoTelemetry {
    private static final Set<String> EVENTS = Set.of("request", "delivered", "deferred", "soft_bounce",
            "hard_bounce", "blocked", "error", "invalid");
    private final JdbcTemplate db;
    public BrevoTelemetry(JdbcTemplate db) { this.db = db; }
    public void accepted(String messageId, Instant at) {
        String id = normalizeId(messageId);
        try { db.update("insert into mail_acceptances (message_id, accepted_at) values (?, ?)", id, Timestamp.from(at)); }
        catch (DuplicateKeyException duplicate) { /* misma aceptacion */ }
    }
    public void event(String messageId, String type, Instant at) {
        String id = normalizeId(messageId);
        if (!EVENTS.contains(type)) throw new IllegalArgumentException("Evento no soportado");
        if (at.isAfter(Instant.now().plusSeconds(300)) || at.isBefore(Instant.parse("2000-01-01T00:00:00Z")))
            throw new IllegalArgumentException("Fecha invalida");
        String key = Hash.sha256(id + ":" + type + ":" + at.getEpochSecond());
        try {
            db.update("insert into mail_delivery_events (event_key, message_id, event_type, event_at, received_at) "
                    + "values (?, ?, ?, ?, ?)", key, id, type, Timestamp.from(at), Timestamp.from(Instant.now()));
        } catch (DuplicateKeyException duplicate) { /* restriccion atomica, tambien entre instancias */ }
    }
    private static String normalizeId(String raw) {
        if (raw == null) throw new IllegalArgumentException("Falta message-id");
        String id = raw.strip().replaceAll("^<|>$", "");
        if (id.isBlank() || id.length() > 255 || id.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("message-id invalido");
        return id;
    }
}
