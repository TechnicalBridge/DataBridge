package com.tbridge.payments.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Duration;
import java.time.Instant;

/**
 * El aviso pendiente hacia el servicio de deudas.
 *
 * <p><b>Por que no se avisa y ya.</b> Si el aviso se mandara dentro de la
 * misma transaccion que confirma el pago, una caida de ms-debt dejaria el
 * dinero cobrado y la deuda sin enterarse, para siempre. Aca el aviso se
 * guarda junto con el pago —si una cosa se graba, la otra tambien— y se
 * entrega despues, con reintentos.
 */
@Entity
@Table(name = "debt_notifications")
public class DebtNotification {

    public enum Status { pending, delivered, failed }

    /** 1 min, 5, 30, 2 h, 6 h y 24 h. Despues queda para reenvio a mano. */
    private static final Duration[] ESPERAS = {
            Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30),
            Duration.ofHours(2), Duration.ofHours(6), Duration.ofHours(24),
    };

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "payment_id", nullable = false, unique = true)
    private Long paymentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.pending;

    //  SMALLINT en la base: un contador de intentos no necesita 4 bytes. La
    //  entidad lo refleja, porque con ddl-auto: validate el servicio no
    //  arranca si los tipos no calzan.
    @Column(nullable = false)
    private Short attempts = 0;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt = Instant.now();

    @Column(name = "delivered_at")
    private Instant deliveredAt;

    @Column(name = "last_error", length = 300)
    private String lastError;

    public static DebtNotification para(Long paymentId) {
        DebtNotification aviso = new DebtNotification();
        aviso.paymentId = paymentId;
        return aviso;
    }

    public void entregado() {
        this.status = Status.delivered;
        this.deliveredAt = Instant.now();
        this.lastError = null;
    }

    /**
     * Lo vuelve a poner en cola para ahora, desde el panel (#139). Conserva los
     * intentos: si vuelve a fallar despues del sexto, queda otra vez como fallido.
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

    public Long getId() {
        return id;
    }

    public Long getPaymentId() {
        return paymentId;
    }

    public void setPaymentId(Long paymentId) {
        this.paymentId = paymentId;
    }

    public Status getStatus() {
        return status;
    }

    public void setStatus(Status status) {
        this.status = status;
    }

    public Short getAttempts() {
        return attempts;
    }

    public void setAttempts(Short attempts) {
        this.attempts = attempts;
    }

    public Instant getNextAttemptAt() {
        return nextAttemptAt;
    }

    public Instant getDeliveredAt() {
        return deliveredAt;
    }

    public String getLastError() {
        return lastError;
    }
}
