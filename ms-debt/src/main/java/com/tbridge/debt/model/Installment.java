package com.tbridge.debt.model;

import jakarta.persistence.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Las cuotas por pagar.
 *
 * <p>Una deuda sin repactar tiene una sola. Al repactar, las pendientes se
 * anulan y se emiten las nuevas: anular deja el rastro, borrar lo perderia.
 *
 * <p>`paidAt` lo escribe el aviso que llega de ms-payments. Aca no se guarda
 * ningun dato de la pasarela: eso vive en tb_payments.
 */
@Entity
@Table(name = "installments")
public class Installment {

    public enum Status { pending, paid, void_ }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "debt_id", nullable = false)
    private Debt debt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "repactation_id")
    private Repactation repactation;

    @Column(nullable = false)
    private Short number;

    @Column(name = "due_date", nullable = false)
    private LocalDate dueDate;

    @Column(nullable = false, precision = 18, scale = 2)
    private BigDecimal amount;

    /**
     * El interes del convenio que va dentro de la cuota (sistema frances). Cero
     * en una cuota sin convenio, o en un convenio sin interes.
     */
    @Column(name = "interest_amount", nullable = false, precision = 18, scale = 2)
    private BigDecimal interestAmount = BigDecimal.ZERO;

    /**
     * 'void' es palabra reservada en Java, asi que la constante se llama
     * void_ y se guarda con su nombre real mediante el conversor de abajo.
     */
    @Convert(converter = StatusConverter.class)
    @Column(nullable = false, length = 10)
    private Status status = Status.pending;

    @Column(name = "paid_at")
    private Instant paidAt;

    /** Cuando se le recordo al deudor que vencia. Una vez por cuota. */
    @Converter
    public static class StatusConverter implements AttributeConverter<Status, String> {
        @Override
        public String convertToDatabaseColumn(Status status) {
            if (status == null) { return null; }
            return status == Status.void_ ? "void" : status.name();
        }

        @Override
        public Status convertToEntityAttribute(String valor) {
            if (valor == null) { return null; }
            return "void".equals(valor) ? Status.void_ : Status.valueOf(valor);
        }
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Debt getDebt() { return debt; }
    public void setDebt(Debt debt) { this.debt = debt; }
    public Repactation getRepactation() { return repactation; }
    public void setRepactation(Repactation repactation) { this.repactation = repactation; }
    /** Si es cuota de un convenio. Lee la columna, sin cargar el convenio. */
    public boolean enConvenio() { return repactation != null; }
    public Short getNumber() { return number; }
    public void setNumber(Short number) { this.number = number; }
    public LocalDate getDueDate() { return dueDate; }
    public void setDueDate(LocalDate dueDate) { this.dueDate = dueDate; }
    public BigDecimal getAmount() { return amount; }
    public void setAmount(BigDecimal amount) { this.amount = amount; }
    public BigDecimal getInterestAmount() { return interestAmount; }
    public void setInterestAmount(BigDecimal interestAmount) { this.interestAmount = interestAmount; }
    /** Lo que la cuota tiene de capital: la mora de una cuota vencida corre solo sobre esto. */
    public BigDecimal capital() {
        return interestAmount == null ? amount : amount.subtract(interestAmount);
    }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public Instant getPaidAt() { return paidAt; }
    public void setPaidAt(Instant paidAt) { this.paidAt = paidAt; }
}
