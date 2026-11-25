package com.tbridge.debt.model;

import jakarta.persistence.*;
import java.time.Instant;

/** La auditoria conserva identificadores y campos, nunca los valores de contacto. */
@Entity
@Table(name = "contact_updates")
public class ContactUpdate {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(name = "debtor_id", nullable = false)
    private Long debtorId;
    @Column(nullable = false, length = 10)
    private String field;
    @Column(nullable = false, length = 10)
    private String actor = "DEBTOR";
    @Column(nullable = false, length = 30)
    private String action = "CONTACT_UPDATED";
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
    protected ContactUpdate() {}
    public ContactUpdate(Long idDeudor, String campo, Instant ocurrioEn) {
        this.debtorId = idDeudor; this.field = campo; this.occurredAt = ocurrioEn;
    }
    public Long getDebtorId() { return debtorId; }
    public String getField() { return field; }
    public String getActor() { return actor; }
}
