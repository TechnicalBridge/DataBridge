package com.tbridge.auth.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * El codigo que abre el portal del deudor.
 *
 * <p><b>Por que un codigo y no un enlace.</b> Un phisher necesita que hagas
 * clic en SU enlace. Si el mensaje te dice que entres por tu cuenta a un sitio
 * que puedes escribir, buscar o verificar antes, el atacante pierde el control
 * del destino, que era todo lo que tenia. Es la tesis de las secciones 13.3 y
 * 13.5 del documento de APOFYX.
 *
 * <p><b>No identifica una deuda sino a una persona.</b> Al entrar ve todo lo
 * que debe: mandarle cinco codigos a quien debe cinco cosas seria volver al
 * problema de origen.
 *
 * <p>Se guarda la huella, no el codigo. Un respaldo de esta tabla no le sirve
 * a nadie para entrar al portal de nadie.
 */
@Entity
@Table(name = "access_codes")
public class AccessCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "code_hash", nullable = false, columnDefinition = "char(64)")
    private String codeHash;

    @Column(name = "debtor_rut", nullable = false, length = 12)
    private String debtorRut;

    /** Por donde se mando: ["whatsapp","correo"]. Dos canales es la prueba. */
    @Column(nullable = false, columnDefinition = "json")
    private String channels;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt = Instant.now();

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "consumed_at")
    private Instant consumedAt;

    /** Un codigo corto se puede adivinar a fuerza bruta; por eso se cuentan. */
    @Column(nullable = false)
    private Short attempts = 0;

    @Column(name = "max_attempts", nullable = false)
    private Short maxAttempts = 5;

    @Column(name = "issued_for", length = 64)
    private String issuedFor;

    public void fallo() {
        this.attempts = (short) (this.attempts + 1);
    }

    public void consumir() {
        this.consumedAt = Instant.now();
    }

    @Column(name = "contact_changed_at")
    private Instant contactChangedAt;
    public Instant getContactChangedAt() { return contactChangedAt; }
    public void setContactChangedAt(Instant at) { contactChangedAt=at; }

    public Long getId() { return id; }
    public String getCodeHash() { return codeHash; }
    public void setCodeHash(String codeHash) { this.codeHash = codeHash; }
    public String getDebtorRut() { return debtorRut; }
    public void setDebtorRut(String debtorRut) { this.debtorRut = debtorRut; }
    public String getChannels() { return channels; }
    public void setChannels(String channels) { this.channels = channels; }
    public Instant getIssuedAt() { return issuedAt; }
    public void setIssuedAt(Instant issuedAt) { this.issuedAt = issuedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public void setExpiresAt(Instant expiresAt) { this.expiresAt = expiresAt; }
    public Instant getConsumedAt() { return consumedAt; }
    public void setConsumedAt(Instant consumedAt) { this.consumedAt = consumedAt; }
    public Short getAttempts() { return attempts; }
    public void setAttempts(Short attempts) { this.attempts = attempts; }
    public Short getMaxAttempts() { return maxAttempts; }
    public void setIssuedFor(String issuedFor) { this.issuedFor = issuedFor; }
}
