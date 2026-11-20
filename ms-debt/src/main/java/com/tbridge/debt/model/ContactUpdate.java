package com.tbridge.debt.model;

import jakarta.persistence.*;
import java.time.Instant;

/** Audit contains identifiers and field names, never contact values. */
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
    public ContactUpdate(Long debtorId, String field, Instant at) {
        this.debtorId = debtorId; this.field = field; this.occurredAt = at;
    }
    public Long getDebtorId() { return debtorId; }
    public String getField() { return field; }
    public String getActor() { return actor; }
}
