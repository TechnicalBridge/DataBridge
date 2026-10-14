package com.tbridge.debt.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Cada plan de pago que el deudor acepto.
 *
 * <p>No se sobreescribe el anterior: se marca `supersededAt` y se crea otro.
 * Un plan aceptado es un compromiso con fecha, y borrarlo seria perder por que
 * las cuotas son las que son.
 */
@Entity
@Table(name = "repactations")
public class Repactation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "debt_id", nullable = false)
    private Debt debt;

    @Column(nullable = false)
    private Short months;

    @Column(name = "monthly_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal monthlyAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 3)
    private Debt.Currency currency;

    /** La tasa mensual con que se armo el convenio. Null si fue sin interes. */
    @Column(name = "interest_rate", precision = 5, scale = 2)
    private BigDecimal interestRate;

    /** Lo que se repacto: el capital mas la mora acumulada hasta ese dia. */
    @Column(precision = 18, scale = 2)
    private BigDecimal principal;

    /** La CAE (Carga Anual Equivalente) del convenio segun Ley 20.555 y D.S. 43/2012. */
    @Column(precision = 6, scale = 2)
    private BigDecimal cae;

    @Column(name = "accepted_at", nullable = false)
    private Instant acceptedAt = Instant.now();

    @Column(name = "superseded_at")
    private Instant supersededAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Debt getDebt() { return debt; }
    public void setDebt(Debt debt) { this.debt = debt; }
    public void setMonths(Short months) { this.months = months; }
    public void setMonthlyAmount(BigDecimal monthlyAmount) { this.monthlyAmount = monthlyAmount; }
    public void setInterestRate(BigDecimal interestRate) { this.interestRate = interestRate; }
    public void setPrincipal(BigDecimal principal) { this.principal = principal; }
    public BigDecimal getCae() { return cae; }
    public void setCae(BigDecimal cae) { this.cae = cae; }
    public Debt.Currency getCurrency() { return currency; }
    public void setCurrency(Debt.Currency currency) { this.currency = currency; }
    public void setAcceptedAt(Instant acceptedAt) { this.acceptedAt = acceptedAt; }
    public void setSupersededAt(Instant supersededAt) { this.supersededAt = supersededAt; }
}
