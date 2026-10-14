package com.tbridge.auth.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Lo que un admin de DataBridge hizo con una persona desde su panel: que, a
 * quien, quien fue y cuando.
 *
 * <p>No es {@link AccessLog}: ese cuenta quien entro y por donde. Este cuenta
 * quien cambio a quien. Solo se agrega.
 */
@Entity
@Table(name = "admin_log")
public class AdminLog {

    public enum Action { persona_creada, admin_creado, persona_deshabilitada, persona_habilitada }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "admin_email", nullable = false, length = 254)
    private String adminEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private Action action;

    /** La empresa de la persona. Vacio si la persona es un admin de DataBridge. */
    @Column(name = "org_rut", length = 12)
    private String orgRut;

    @Column(length = 254)
    private String subject;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    public static AdminLog de(String admin, Action accion, String orgRut, String sujeto) {
        AdminLog registro = new AdminLog();
        registro.adminEmail = admin;
        registro.action = accion;
        registro.orgRut = orgRut;
        registro.subject = sujeto;
        return registro;
    }

    public Long getId() { return id; }
    public String getAdminEmail() { return adminEmail; }
    public Action getAction() { return action; }
    public String getOrgRut() { return orgRut; }
    public String getSubject() { return subject; }
    public Instant getOccurredAt() { return occurredAt; }
}
