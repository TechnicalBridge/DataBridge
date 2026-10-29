package com.tbridge.debt.model;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.Locale;

/**
 * Quien debe.
 *
 * <p>Una fila por RUT aunque deba a varios acreedores: es la misma persona. El
 * portal se lo muestra todo junto cuando entra con su codigo, y por eso el
 * deudor no puede estar repetido.
 *
 * <p>El modelo anterior lo guardaba como correo y nombre sueltos dentro de
 * cada deuda: si cambiaba de correo, sus deudas quedaban huerfanas.
 *
 * <p>Si un correo a su direccion no sale, queda aca por que: es de la
 * direccion, no de una deuda. Tres rechazos seguidos de la direccion la dejan
 * con problemas, y ya no se le escribe solo hasta que llegue otra.
 */
@Entity
@Table(name = "debtors")
public class Debtor {

    public enum Kind { person, company }

    /** Rechazos seguidos de la direccion despues de los cuales ya no se le escribe solo. */
    public static final int RECHAZOS_PARA_DEJAR = 3;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 12, unique = true)
    private String rut;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Kind kind = Kind.person;

    @Column(name = "full_name", nullable = false, length = 160)
    private String fullName;

    @Column(length = 254)
    private String email;

    /** La ultima vez que un correo a este deudor no salio. Vacio si el ultimo salio. */
    @Column(name = "email_failed_at")
    private Instant emailFailedAt;

    /** Por que no salio, en palabras para la empresa y con la direccion tapada. */
    @Column(name = "email_failure", length = 300)
    private String emailFailure;

    /** Cuantas veces seguidas el servidor rechazo la direccion. Una falla del servidor no cuenta. */
    @Column(name = "email_rejections", nullable = false)
    private short emailRejections;

    @Column(length = 20)
    private String phone;

    /** Si quiere el correo que le recuerda una cuota por vencer. */
    @Column(nullable = false)
    private boolean reminders = true;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getRut() { return rut; }
    public void setRut(String rut) { this.rut = rut; }
    public Kind getKind() { return kind; }
    public void setKind(Kind kind) { this.kind = kind; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public String getEmail() { return email; }

    public void setEmail(String email) {
        //  Un correo nuevo parte de cero: lo que fallaba era con el anterior.
        if (!normalizado(email).equals(normalizado(this.email))) {
            correoSalio();
        }
        this.email = email;
    }

    private static String normalizado(String correo) {
        return correo == null ? "" : correo.strip().toLowerCase(Locale.ROOT);
    }

    /**
     * Un correo a su direccion no salio. Si fue la direccion la rechazada,
     * cuenta para dejar de escribirle; si fue el servidor de correo, no.
     */
    public void correoNoSalio(String motivo, boolean deLaDireccion, Instant cuando) {
        this.emailFailedAt = cuando;
        this.emailFailure = motivo == null || motivo.length() <= 300 ? motivo : motivo.substring(0, 300);
        if (this.emailFailure == null) {
            this.emailFailure = "sin detalle";
        }
        if (deLaDireccion && emailRejections < Short.MAX_VALUE) {
            emailRejections++;
        }
    }

    /** Un correo a su direccion salio: lo que fallaba antes ya no importa. */
    public void correoSalio() {
        if (emailFailedAt != null || emailFailure != null || emailRejections != 0) {
            emailFailedAt = null;
            emailFailure = null;
            emailRejections = 0;
        }
    }

    /** Si la direccion se rechazo tantas veces seguidas que ya no se le escribe solo. */
    public boolean correoConProblemas() {
        return emailRejections >= RECHAZOS_PARA_DEJAR;
    }

    public Instant getEmailFailedAt() { return emailFailedAt; }
    public String getEmailFailure() { return emailFailure; }
    public short getEmailRejections() { return emailRejections; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public boolean isReminders() { return reminders; }
    public void setReminders(boolean reminders) { this.reminders = reminders; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Instant updatedAt) { this.updatedAt = updatedAt; }
}
