package com.tbridge.auth.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * Una llave de renovacion de la sesion.
 *
 * <p>Nace cuando alguien entra con su codigo o su enlace, y cada vez que se
 * usa se jubila y deja una hija en la misma familia. Ver
 * {@code V2__sesiones.sql} para las reglas completas.
 */
@Entity
@Table(name = "sessions")
public class Session {

    /** El deudor, la persona de una empresa usuaria y el admin de DataBridge. */
    public enum Role { DEBTOR, CREDITOR, ADMIN }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "family_id", nullable = false, columnDefinition = "char(36)")
    private String familyId;

    @Column(name = "refresh_hash", nullable = false, columnDefinition = "char(64)")
    private String refreshHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Role role;

    @Column(length = 12)
    private String rut;

    @Column(length = 254)
    private String email;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "rotated_at")
    private Instant rotatedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "ip_hash", columnDefinition = "char(64)")
    private String ipHash;

    public boolean revocada() {
        return revokedAt != null;
    }

    public boolean rotada() {
        return rotatedAt != null;
    }

    public boolean vencida(Instant ahora) {
        return !ahora.isBefore(expiresAt);
    }

    public Long getId() { return id; }
    public String getFamilyId() { return familyId; }
    public void setFamilyId(String familyId) { this.familyId = familyId; }
    public String getRefreshHash() { return refreshHash; }
    public void setRefreshHash(String refreshHash) { this.refreshHash = refreshHash; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public String getRut() { return rut; }
    public void setRut(String rut) { this.rut = rut; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public Instant getIssuedAt() { return issuedAt; }
    public void setIssuedAt(Instant issuedAt) { this.issuedAt = issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getRotatedAt() { return rotatedAt; }
    public void setRotatedAt(Instant rotatedAt) { this.rotatedAt = rotatedAt; }
    public Instant getRevokedAt() { return revokedAt; }
    public void setRevokedAt(Instant revokedAt) { this.revokedAt = revokedAt; }
    public String getIpHash() { return ipHash; }
    public void setIpHash(String ipHash) { this.ipHash = ipHash; }
}
