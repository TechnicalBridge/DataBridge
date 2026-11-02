package com.tbridge.debt.model;

import jakarta.persistence.*;
import java.time.Duration;
import java.time.Instant;

/**
 * Un evento por entregar a un suscriptor.
 *
 * <p>Se escribe en la misma transaccion que produce el hecho —el pago
 * aplicado, el plan aceptado—, asi que no puede existir un pago sin su aviso
 * ni un aviso de un pago que se deshizo. El despachador lo entrega despues.
 */
@Entity
@Table(name = "outbox")
public class OutboxEvent {

    public enum Status { pending, delivered, failed }

    /** 1 min, 5, 30, 2 h, 6 h y 24 h. Despues queda para reenvio a mano. */
    private static final Duration[] ESPERAS = {
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
            Duration.ofHours(2), Duration.ofHours(6), Duration.ofHours(24),
    };

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    //  CHAR(36) en la base. Sin columnDefinition, Hibernate espera VARCHAR y
    //  `validate` no deja arrancar el servicio.
    @Column(name = "event_id", nullable = false, columnDefinition = "char(36)")
    private String eventId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "subscription_id", nullable = false)
    private Subscription subscription;

    @Column(nullable = false, length = 30)
    private String type;

    @Column(nullable = false, columnDefinition = "json")
    private String payload;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.pending;

    @Column(nullable = false)
    private Short attempts = 0;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "last_error", length = 300)
    private String lastError;

    public static OutboxEvent para(Subscription suscripcion, String eventId, String tipo,
                                   String payload, Instant ocurrido) {
        OutboxEvent evento = new OutboxEvent();
        evento.subscription = suscripcion;
        evento.eventId = eventId;
        evento.type = tipo;
        evento.payload = payload;
        evento.occurredAt = ocurrido;
        return evento;
    }

    public void entregado() {
        this.status = Status.delivered;
        this.deliveredAt = Instant.now();
        this.lastError = null;
    }

    /**
     * Lo vuelve a poner en cola para ahora, desde el panel (#139). Conserva los
     * intentos: si vuelve a fallar despues del sexto, queda otra vez como fallido
     * y no entra en otra vuelta de reintentos solos.
     */
    public void reintentar() {
        this.status = Status.pending;
        this.nextAttemptAt = Instant.now();
    }

    /** Programa el proximo intento, o se rinde despues del ultimo. */
    public void fallo(String motivo) {
        this.attempts = (short) (this.attempts + 1);
        this.lastError = motivo == null ? null
                : motivo.substring(0, Math.min(motivo.length(), 300));
        if (attempts >= ESPERAS.length) {
            this.status = Status.failed;
            return;
        }
        this.nextAttemptAt = Instant.now().plus(ESPERAS[attempts - 1]);
    }

    public Long getId() { return id; }
    public String getEventId() { return eventId; }
    public Subscription getSubscription() { return subscription; }
    public String getType() { return type; }
    public String getPayload() { return payload; }
    public Instant getOccurredAt() { return occurredAt; }
    public Status getStatus() { return status; }
    public Short getAttempts() { return attempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public String getLastError() { return lastError; }
}
