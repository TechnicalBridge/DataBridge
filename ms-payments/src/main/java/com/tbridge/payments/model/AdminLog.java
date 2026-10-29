package com.tbridge.payments.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Lo que un admin de DataBridge hizo desde su panel con los pagos (#139): que,
 * sobre que, quien y cuando.
 *
 * <p>Solo se agrega. El admin se anota por su correo y no por su id: vive en
 * tb_auth, que es otra base, y un correo se entiende al leerlo.
 */
@Entity
@Table(name = "admin_log")
public class AdminLog {

    //  aviso_reintentado: un aviso de pago a ms-debt que se volvio a poner en cola.
    //  uf_cargada: la UF de un dia, cargada a mano.
    public enum Action { aviso_reintentado, uf_cargada }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "admin_email", nullable = false, length = 254)
    private String adminEmail;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private Action action;

    @Column(length = 254)
    private String subject;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    public static AdminLog de(String admin, Action accion, String sujeto) {
        AdminLog registro = new AdminLog();
        registro.adminEmail = admin;
        registro.action = accion;
        registro.subject = sujeto;
        return registro;
    }

    public Long getId() { return id; }
    public String getAdminEmail() { return adminEmail; }
    public Action getAction() { return action; }
    public String getSubject() { return subject; }
    public Instant getOccurredAt() { return occurredAt; }
}
