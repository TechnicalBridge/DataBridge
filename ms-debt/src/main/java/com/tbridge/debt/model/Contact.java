package com.tbridge.debt.model;

import jakarta.persistence.*;

import java.time.Instant;

/**
 * Una vez que DataBridge le escribio a un deudor.
 *
 * <p>Son las gestiones de cobranza que la ley manda registrar y limitar
 * (Ley 19.496, art. 37): la invitacion, un toque de campana, el recordatorio
 * de una cuota y el codigo que reenvia la empresa. Antes eran eventos
 * {@code code_sent} de la deuda, con la campana y el toque dentro de un JSON;
 * aca cada dato tiene su columna y su llave.
 *
 * <p>El deudor no se repite: es el de la deuda. La base cuida ademas que un
 * toque de una campana salga una sola vez por deuda y que una cuota se
 * recuerde una sola vez.
 *
 * <p>Solo se crea con una de sus cuatro formas, que es lo que deja cada tipo
 * con lo suyo y nada mas: un toque siempre trae su campana y su numero, y un
 * recordatorio, su cuota.
 */
@Entity
@Table(name = "contacts")
public class Contact {

    public enum Kind {
        /** El primer aviso, apenas la deuda entra en cobranza. */
        invitation,
        /** Un toque de su campana. */
        campaign,
        /** El recordatorio de una cuota por vencer. */
        reminder,
        /** El codigo que reenvia a mano una persona de la empresa. */
        resend
    }

    /** Por donde se le escribio. Hoy, solo por correo. */
    public enum Channel { email }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "debt_id", nullable = false)
    private Debt debt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private Kind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private Channel channel = Channel.email;

    /** A donde, con la direccion tapada. Vacio en los contactos antiguos que no lo guardaron. */
    @Column(length = 254)
    private String destination;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id")
    private Campaign campaign;

    @Column(name = "touch_number")
    private Short touchNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "installment_id")
    private Installment installment;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    protected Contact() {
    }

    private Contact(Debt deuda, Kind tipo, String destino) {
        this.debt = deuda;
        this.kind = tipo;
        this.destination = destino;
    }

    public static Contact invitacion(Debt deuda, String destino) {
        return new Contact(deuda, Kind.invitation, destino);
    }

    /** El toque {@code numero} (1, 2...) de la campana para esa deuda. */
    public static Contact toque(Debt deuda, Campaign campana, int numero, String destino) {
        Contact contacto = new Contact(deuda, Kind.campaign, destino);
        contacto.campaign = campana;
        contacto.touchNumber = (short) numero;
        return contacto;
    }

    public static Contact recordatorio(Installment cuota, String destino) {
        Contact contacto = new Contact(cuota.getDebt(), Kind.reminder, destino);
        contacto.installment = cuota;
        return contacto;
    }

    public static Contact reenvio(Debt deuda, String destino) {
        return new Contact(deuda, Kind.resend, destino);
    }

    /** Con otra fecha que la de ahora: una pasada de prueba, o la historia de la demostracion. */
    public Contact el(Instant cuando) {
        this.occurredAt = cuando;
        return this;
    }

    /** Si lo mando una persona de la empresa, y no DataBridge por su cuenta. */
    public boolean deLaEmpresa() {
        return kind == Kind.resend;
    }

    public Long getId() { return id; }
    public Debt getDebt() { return debt; }
    public Kind getKind() { return kind; }
    public Channel getChannel() { return channel; }
    public String getDestination() { return destination; }
    public Campaign getCampaign() { return campaign; }
    public Short getTouchNumber() { return touchNumber; }
    public Installment getInstallment() { return installment; }
    public Instant getOccurredAt() { return occurredAt; }
}
