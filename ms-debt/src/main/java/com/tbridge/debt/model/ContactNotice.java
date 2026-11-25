package com.tbridge.debt.model;
import jakarta.persistence.*;
import java.time.Instant;

/** Bandeja privada y duradera, independiente de las suscripciones de webhooks de acreedores. */
@Entity @Table(name = "contact_notices")
public class ContactNotice {
    @Id @Column(length = 36) public String id;
    @Convert(converter = SecretoCifrado.class)
    @Column(name = "old_email", length = 1024) public String oldEmail;
    /** El codigo para deshacer el cambio, cifrado hasta entregarlo; despues se borra (#471). */
    @Convert(converter = SecretoCifrado.class)
    @Column(name = "revert_code", length = 1024) public String revertCode;
    @Column(nullable = false) public int attempts;
    @Column(name = "next_attempt_at", nullable = false) public Instant nextAttemptAt;
    @Column(name = "delivered_at") public Instant deliveredAt;
}
