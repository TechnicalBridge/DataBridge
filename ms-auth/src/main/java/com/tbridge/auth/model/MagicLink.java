package com.tbridge.auth.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * La solicitud de acceso: codigo y, solo en el respaldo, enlace alternativo.
 * Ambos comparten intentos, vencimiento y consumo en una misma fila. Los
 * enlaces anteriores a V6 no tienen codigo y siguen siendo de un solo uso.
 */
@Entity
@Table(name = "magic_links")
public class MagicLink {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "token_hash", nullable = false, columnDefinition = "char(64)")
    private String tokenHash;

    @Column(name = "debtor_rut", length = 12)
    private String debtorRut;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    @Column(name = "code_hash", columnDefinition = "char(64)")
    private String codeHash;

    @Column(nullable = false)
    private Short attempts = 0;

    @Column(name = "max_attempts", nullable = false)
    private Short maxAttempts = 5;

    @Column(name = "link_enabled", nullable = false)
    private boolean linkEnabled = true;

    public boolean vigente() {
        return consumedAt == null && attempts < maxAttempts && Instant.now().isBefore(expiresAt);
    }

    public void fallo() { attempts++; }

    public Long getId() { return id; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }
    public String getDebtorRut() { return debtorRut; }
    public void setDebtorRut(String debtorRut) { this.debtorRut = debtorRut; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public Instant getIssuedAt() { return issuedAt; }
    public void setIssuedAt(Instant issuedAt) { this.issuedAt = issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }
    public void setConsumedAt(Instant consumedAt) { this.consumedAt = consumedAt; }
    public String getCodeHash() { return codeHash; }
    public void setCodeHash(String codeHash) { this.codeHash = codeHash; }
    public Short getAttempts() { return attempts; }
    public Short getMaxAttempts() { return maxAttempts; }
    public boolean isLinkEnabled() { return linkEnabled; }
    public void setLinkEnabled(boolean linkEnabled) { this.linkEnabled = linkEnabled; }
}
