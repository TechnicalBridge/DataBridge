package com.tbridge.auth.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Quien entra al portal de una empresa: el operador de la agencia, el
 * administrativo del acreedor.
 *
 * <p>Estos SI tienen cuenta, porque vuelven todos los dias y necesitan
 * permisos. Lo que no tienen es contrasena: entran por enlace.
 *
 * <p>`orgRut` es una referencia logica a organizations.rut de tb_debt. No hay
 * clave foranea porque son bases de servicios distintos, y el RUT es
 * justamente la identidad que sirve entre sistemas.
 */
@Entity
@Table(name = "staff_users")
public class StaffUser {

    /**
     * {@code admin} es de DataBridge y entra al panel de administracion;
     * {@code usuario} es de una empresa usuaria y entra al portal de su empresa.
     * Un admin nunca tiene empresa y una persona de una empresa nunca es admin:
     * lo exige tambien la base (V4).
     */
    public enum Role { usuario, admin }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** La empresa de un usuario. Un admin no tiene: es de DataBridge. */
    @Column(name = "org_rut", length = 12)
    private String orgRut;

    @Column(nullable = false, length = 254, unique = true)
    private String email;

    @Column(name = "full_name", nullable = false, length = 160)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Role role = Role.usuario;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "disabled_at")
    private Instant disabledAt;

    public boolean habilitado() {
        return disabledAt == null;
    }

    public boolean esAdmin() {
        return role == Role.admin;
    }

    public Long getId() { return id; }
    public String getOrgRut() { return orgRut; }
    public void setOrgRut(String orgRut) { this.orgRut = orgRut; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public Role getRole() { return role; }
    public void setRole(Role role) { this.role = role; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
    public void setLastLoginAt(Instant lastLoginAt) { this.lastLoginAt = lastLoginAt; }
}
