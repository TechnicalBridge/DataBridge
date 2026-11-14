package com.tbridge.debt.model;

import jakarta.persistence.*;
import java.time.Instant;

/** Intencion durable, sin credenciales ni copia del correo. ACCEPTED no significa delivered. */
@Entity
@Table(name="contact_jobs")
public class ContactJob {
    public static final int MAX_DISPATCH_ATTEMPTS = 3;
    public enum State { QUEUED, SENDING, ACCEPTED, FAILED, UNCERTAIN, CANCELLED }
    @Id @GeneratedValue(strategy=GenerationType.IDENTITY) private Long id;
    @Column(name="job_key", nullable=false, unique=true, length=160) private String jobKey;
    @ManyToOne(fetch=FetchType.LAZY, optional=false) @JoinColumn(name="debt_id") private Debt debt;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=12) private Contact.Kind kind;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="campaign_id") private Campaign campaign;
    @Column(name="touch_number") private Short touchNumber;
    @ManyToOne(fetch=FetchType.LAZY) @JoinColumn(name="installment_id") private Installment installment;
    @Enumerated(EnumType.STRING) @Column(nullable=false, length=12) private State state = State.QUEUED;
    @Column(name="destination_hash", nullable=false, columnDefinition="char(64)") private String destinationHash;
    @Column(name="created_at", nullable=false) private Instant createdAt = Instant.now();
    @Column(name="updated_at", nullable=false) private Instant updatedAt = Instant.now();
    @Column(name="dispatch_attempts", nullable=false) private int dispatchAttempts;
    @Column(name="next_attempt_at", nullable=false) private Instant nextAttemptAt = Instant.now();
    protected ContactJob() {}
    public ContactJob(Contact contact, String hash) {
        debt=contact.getDebt(); kind=contact.getKind(); campaign=contact.getCampaign();
        touchNumber=contact.getTouchNumber(); installment=contact.getInstallment(); destinationHash=hash;
        jobKey=key(contact);
    }
    public static String key(Contact c) {
        if (c.getKind()==Contact.Kind.invitation) return "invitation:"+c.getDebt().getId()+":"
                +(c.getDebt().getLastBatch()==null?"initial":c.getDebt().getLastBatch().getId());
        if (c.getKind()==Contact.Kind.resend) return "resend:"+c.getDebt().getId()+":"+java.util.UUID.randomUUID();
        return c.getKind()+":"+c.getDebt().getId()+":"+(c.getCampaign()==null?"":c.getCampaign().getId())
                +":"+c.getTouchNumber()+":"+(c.getInstallment()==null?"":c.getInstallment().getId());
    }
    public Contact contact(String destination) {
        return switch(kind) {
            case invitation -> Contact.invitacion(debt,destination);
            case campaign -> Contact.toque(debt,campaign,touchNumber,destination);
            case reminder -> Contact.recordatorio(installment,destination);
            case resend -> Contact.reenvio(debt,destination);
        };
    }
    public boolean ready(Instant now) { return !now.isBefore(nextAttemptAt); }
    public void startAttempt() { dispatchAttempts++; state(State.SENDING); }
    public boolean retryBeforeSend(Instant now) {
        if (dispatchAttempts >= MAX_DISPATCH_ATTEMPTS) { state(State.FAILED); return false; }
        nextAttemptAt = now.plusSeconds(30L << Math.max(0, dispatchAttempts - 1));
        state(State.QUEUED);
        return true;
    }
    public void correctedDestination(String hash) {
        destinationHash=hash; dispatchAttempts=0; nextAttemptAt=Instant.now(); state(State.QUEUED);
    }
    public int getDispatchAttempts() { return dispatchAttempts; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public void state(State value) { state=value; updatedAt=Instant.now(); }
    public Long getId() { return id; }
    public Debt getDebt() { return debt; }
    public Contact.Kind getKind() { return kind; }
    public Campaign getCampaign() { return campaign; }
    public Installment getInstallment() { return installment; }
    public State getState() { return state; }
    public String getDestinationHash() { return destinationHash; }
}
