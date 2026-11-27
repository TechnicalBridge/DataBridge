package com.tbridge.debt.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * El codigo para deshacer un cambio de correo que el deudor no pidio (#471). Llega al correo anterior en el aviso
 * y vale 7 dias. Guarda el correo anterior cifrado hasta que se usa o vence; el codigo, solo como huella.
 */
@Entity @Table(name = "contact_reversions")
public class ContactReversion {
    @Id @Column(length = 36) public String id;
    @Column(name = "debtor_id", nullable = false) public Long debtorId;
    @Column(nullable = false, length = 12) public String rut;
    @Convert(converter = SecretoCifrado.class)
    @Column(name = "previous_email", length = 1024) public String previousEmail;
    @Column(name = "code_hash", nullable = false, length = 64) public String codeHash;
    @Column(name = "expires_at", nullable = false) public Instant expiresAt;
    @Column(nullable = false, length = 12) public String status;
    @Column(nullable = false) public int attempts;
    @Column(name = "created_at", nullable = false) public Instant createdAt;
    @Column(name = "used_at") public Instant usedAt;
}
