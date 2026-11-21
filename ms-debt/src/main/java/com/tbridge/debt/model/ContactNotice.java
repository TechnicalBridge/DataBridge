package com.tbridge.debt.model;
import jakarta.persistence.*;
import java.time.Instant;

/** Durable private outbox, independent of creditor webhook subscriptions. */
@Entity @Table(name = "contact_notices")
public class ContactNotice {
    @Id @Column(length = 36) public String id;
    @Convert(converter = SecretoCifrado.class)
    @Column(name = "old_email", length = 1024) public String oldEmail;
    @Column(nullable = false) public int attempts;
    @Column(name = "next_attempt_at", nullable = false) public Instant nextAttemptAt;
    @Column(name = "delivered_at") public Instant deliveredAt;
}
