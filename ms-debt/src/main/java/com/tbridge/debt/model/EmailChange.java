package com.tbridge.debt.model;
import jakarta.persistence.*;
import java.time.Instant;

/** Desafio de propiedad pendiente; los codigos son HMAC y las direcciones quedan cifradas en reposo. */
@Entity @Table(name = "email_changes")
public class EmailChange {
    @Id @Column(length = 36) public String id;
    @Column(name = "debtor_id", nullable = false) public Long debtorId;
    @Column(nullable = false, length = 12) public String rut;
    @Convert(converter = SecretoCifrado.class)
    @Column(name = "new_email", nullable = false, length = 1024) public String newEmail;
    @Column(name = "code_hash", nullable = false, length = 64) public String codeHash;
    @Column(name = "issued_at", nullable = false) public Instant issuedAt;
    @Column(name = "expires_at", nullable = false) public Instant expiresAt;
    @Column(nullable = false, length = 12) public String status;
    @Column(nullable = false) public int attempts;
}
