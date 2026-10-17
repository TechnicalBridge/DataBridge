package com.tbridge.debt.model;

import jakarta.persistence.*;

import java.time.LocalDate;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * Una agencia cobra por cuenta de un acreedor.
 *
 * <p>Es lo que autoriza a APOFYX a entregar cartera de Patrimonio. Sin mandato
 * vigente, una cartera a nombre de otro se rechaza.
 *
 * <p>`maxOverdueDays` lo fija la agencia, no DataBridge: los 120 dias son la
 * regla de APOFYX —pasados esos, devuelve el caso al acreedor— y otra agencia
 * podria usar otra.
 */
@Entity
@Table(name = "mandates")
public class Mandate {

    public enum Status { active, ended }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "agency_id", nullable = false)
    private Organization agency;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "creditor_id", nullable = false)
    private Organization creditor;

    @Column(name = "valid_from", nullable = false)
    private LocalDate validFrom;

    @Column(name = "valid_to")
    private LocalDate validTo;

    @Column(name = "max_overdue_days", nullable = false)
    private Short maxOverdueDays = 120;

    /** El % de los intereses de mora que el acreedor autoriza condonar. Null es 0. */
    @Column(name = "max_mora_discount", precision = 5, scale = 2)
    private BigDecimal maxMoraDiscount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Status status = Status.active;

    @Column(name = "declared_by", length = 160)
    private String declaredBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    public boolean vigenteAl(LocalDate dia) {
        return status == Status.active
                && !dia.isBefore(validFrom)
                && (validTo == null || !dia.isAfter(validTo));
    }

    public Long getId() { return id; }
    public Organization getAgency() { return agency; }
    public void setAgency(Organization agency) { this.agency = agency; }
    public Organization getCreditor() { return creditor; }
    public void setCreditor(Organization creditor) { this.creditor = creditor; }
    public LocalDate getValidFrom() { return validFrom; }
    public void setValidFrom(LocalDate validFrom) { this.validFrom = validFrom; }
    public LocalDate getValidTo() { return validTo; }
    public void setValidTo(LocalDate validTo) { this.validTo = validTo; }
    public Short getMaxOverdueDays() { return maxOverdueDays; }
    public void setMaxOverdueDays(Short maxOverdueDays) { this.maxOverdueDays = maxOverdueDays; }
    public BigDecimal getMaxMoraDiscount() { return maxMoraDiscount; }
    public void setMaxMoraDiscount(BigDecimal maxMoraDiscount) { this.maxMoraDiscount = maxMoraDiscount; }
    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }
    public void setDeclaredBy(String declaredBy) { this.declaredBy = declaredBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
