package com.tbridge.debt.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Lo que un admin de DataBridge hizo desde su panel: que, sobre que empresa,
 * quien y cuando.
 *
 * <p>Solo se agrega. El admin se anota por su correo y no por su id: vive en
 * tb_auth, que es otra base, y un correo se entiende al leerlo.
 */
@Entity
@Table(name = "admin_log")
public class AdminLog {

    public enum Action { empresa_creada, empresa_suspendida, empresa_reactivada }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "admin_email", nullable = false, length = 254)
    private String adminEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private Action action;

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
