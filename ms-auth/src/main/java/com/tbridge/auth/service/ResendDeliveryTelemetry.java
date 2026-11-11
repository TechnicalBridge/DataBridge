package com.tbridge.auth.service;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

@Service
public class ResendDeliveryTelemetry {
    private final JdbcTemplate jdbc;

    public ResendDeliveryTelemetry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void aceptar(UUID mensaje, Instant instante) {
        jdbc.update("""
                INSERT INTO resend_acceptances (message_id, accepted_at) VALUES (?, ?)
                ON DUPLICATE KEY UPDATE message_id = message_id
                """, mensaje.toString(), Timestamp.from(instante));
    }

    public void registrar(String evento, UUID mensaje, String tipo, Instant instante, Instant recibido) {
        jdbc.update("""
                INSERT INTO resend_delivery_events (event_id, message_id, event_type, event_at, received_at)
                VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE event_id = event_id
                """, evento, mensaje.toString(), tipo, Timestamp.from(instante), Timestamp.from(recibido));
    }
}
