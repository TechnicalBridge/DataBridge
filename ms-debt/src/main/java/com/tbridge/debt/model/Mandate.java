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
 *
 * <p>Con la API apagada, la agencia lo declara, lo renueva y lo termina en su
 * portal (#234), y queda quien lo hizo. Terminado o vencido, lo ya cargado se
 * sigue cobrando: solo deja de poder cargarse cartera y crearse campanas.
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

    /** La ultima vez que la agencia le cambio la fecha de termino, y quien. */
    @Column(name = "renewed_at")
    private Instant renewedAt;

    @Column(name = "renewed_by", length = 160)
    private String renewedBy;

    /** Cuando la agencia lo termino, y quien. Solo en uno terminado. */
    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "ended_by", length = 160)
    private String endedBy;

    public boolean vigenteAl(LocalDate dia) {
        return status == Status.active
                && !dia.isBefore(validFrom)
                && (validTo == null || !dia.isAfter(validTo));
    }

    /** Como esta ese dia: vigente, por_empezar, vencido o terminado. */
    public String estadoAl(LocalDate dia) {
        if (status == Status.ended) {
            return "terminado";
        }
        if (dia.isBefore(validFrom)) {
            return "por_empezar";
        }
        return validTo != null && dia.isAfter(validTo) ? "vencido" : "vigente";
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
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
    public String getDeclaredBy() { return declaredBy; }
    public void setDeclaredBy(String declaredBy) { this.declaredBy = declaredBy; }
    public Instant getRenewedAt() { return renewedAt; }
    public void setRenewedAt(Instant renewedAt) { this.renewedAt = renewedAt; }
    public String getRenewedBy() { return renewedBy; }
    public void setRenewedBy(String renewedBy) { this.renewedBy = renewedBy; }
    public Instant getEndedAt() { return endedAt; }
    public void setEndedAt(Instant endedAt) { this.endedAt = endedAt; }
    public String getEndedBy() { return endedBy; }
    public void setEndedBy(String endedBy) { this.endedBy = endedBy; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
