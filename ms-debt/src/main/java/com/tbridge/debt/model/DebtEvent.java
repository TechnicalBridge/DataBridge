package com.tbridge.debt.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * La auditoria de la deuda, en columnas.
 *
 * <p>El modelo anterior guardaba esto como texto armado a mano:
 * <pre>"pago_exitoso 50000 CLP via webpay (paymentId=abc)"</pre>
 * Eso no se puede consultar: no se puede sumar, ni filtrar por medio, ni
 * cruzar con la pasarela. Aca cada dato tiene su columna, y lo que no calza en
 * ninguna va en {@code detail} (JSON): hoy, el {@link DetallePago} de cada
 * pago aplicado.
 *
 * <p>Lo que se le escribe al deudor no va aca: cada contacto tiene su fila en
 * {@link Contact}, con su campana, su toque o su cuota.
 */
@Entity
@Table(name = "debt_events")
public class DebtEvent {

    public enum Type {
        registered, updated, withdrawn, portal_entered,
        repacted, payment_applied, settled, disputed
    }

    public enum Actor { debtor, creditor, agency, system }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "debt_id", nullable = false)
    private Debt debt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Type type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Actor actor;

    @Column(precision = 18, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(length = 3)
    private Debt.Currency currency;

    @Column(length = 80)
    private String reference;

    @Column(columnDefinition = "json")
    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    public static DebtEvent de(Debt debt, Type type, Actor actor) {
        DebtEvent evento = new DebtEvent();
        evento.debt = debt;
        evento.type = type;
        evento.actor = actor;
        return evento;
    }

    public DebtEvent conMonto(BigDecimal monto, Debt.Currency moneda) {
        this.amount = monto;
        this.currency = moneda;
        return this;
    }

    public DebtEvent conReferencia(String referencia) {
        this.reference = referencia;
        return this;
    }

    /** El detalle en JSON, ya serializado. */
    public DebtEvent conDetalle(String json) {
        this.detail = json;
        return this;
    }

    public Long getId() { return id; }
    public Debt getDebt() { return debt; }
    public void setDebt(Debt debt) { this.debt = debt; }
    public Type getType() { return type; }
    public void setType(Type type) { this.type = type; }
    public Actor getActor() { return actor; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public Debt.Currency getCurrency() { return currency; }
    public void setCurrency(Debt.Currency currency) { this.currency = currency; }
    public String getReference() { return reference; }
    public String getDetail() { return detail; }
    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }
}
