package com.tbridge.debt.service;

import com.tbridge.debt.client.AuthClient;
import com.tbridge.debt.model.*;
import com.tbridge.debt.repository.*;
import java.time.*;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Solo operaciones DB cortas: ningun metodo llama al proveedor. */
@Service
public class ContactDispatchTransactions {
    private final ContactJobRepository jobs;
    private final DebtorRepository debtors;
    private final ContactRepository contacts;
    private final HorarioDeContacto horario;
    private final LimiteDeContacto limite;
    private final TopeDeInvitaciones tope;
    public record Dispatch(Long id, AuthClient.PedidoDeCodigo request) {}
    public ContactDispatchTransactions(ContactJobRepository jobs, DebtorRepository debtors, ContactRepository contacts,
            HorarioDeContacto horario, LimiteDeContacto limite, TopeDeInvitaciones tope) {
        this.jobs=jobs; this.debtors=debtors; this.contacts=contacts; this.horario=horario; this.limite=limite; this.tope=tope;
    }
    private ContactJob lockJob(Long id) {
        Long debtorId = jobs.debtorId(id).orElse(null);
        if (debtorId == null) return null;
        debtors.locked(debtorId).orElseThrow();
        return jobs.locked(id).orElse(null);
    }
    @Transactional
    public void recover() { jobs.recover(Instant.now().minusSeconds(120)); }
    @Transactional
    public Dispatch claim(Long id) {
        ContactJob job=lockJob(id);
        if(job==null || job.getState()!=ContactJob.State.QUEUED) return null;
        Debt debt=job.getDebt();
        Debtor debtor=debtors.locked(debt.getDebtor().getId()).orElseThrow();
        if(debt.congelada() || (job.getKind()!=Contact.Kind.resend && debtor.correoConProblemas()) || debtor.getEmail()==null
                || !ContactOutbox.hash(debtor.getEmail()).equals(job.getDestinationHash())
                || (job.getKind()==Contact.Kind.reminder
                    ? (debt.getStatus()!=Debt.Status.open && debt.getStatus()!=Debt.Status.repacted)
                    : job.getKind()==Contact.Kind.resend
                        ? (debt.getStatus()==Debt.Status.paid || debt.getStatus()==Debt.Status.withdrawn)
                        : debt.getStatus()!=Debt.Status.open)) {
            job.state(ContactJob.State.CANCELLED); return null;
        }
        LocalDate hoy=LocalDate.now(HorarioDeContacto.CHILE);
        if(job.getInstallment()!=null && (!debtor.isReminders()
                || job.getInstallment().getStatus()!=Installment.Status.pending
                || !job.getInstallment().getDueDate().isAfter(hoy))) {
            job.state(ContactJob.State.CANCELLED); return null;
        }
        Campaign campaign=job.getCampaign();
        if(campaign!=null && (campaign.getStatus()!=Campaign.Status.running
                || hoy.isBefore(campaign.getStartsOn()) || (campaign.getEndsOn()!=null && hoy.isAfter(campaign.getEndsOn()))
                || !campaign.getChannels().contains("\"correo\""))) {
            job.state(ContactJob.State.CANCELLED); return null;
        }
        if(!horario.permiteAhora() || !horario.permite(LocalDateTime.now(HorarioDeContacto.CHILE).plusSeconds(30))
                || !limite.permiteSinReserva(debtor,Instant.now())
                || !jobs.reserved(debtor.getId(),List.of(ContactJob.State.SENDING,ContactJob.State.UNCERTAIN)).isEmpty()) {
            job.state(ContactJob.State.QUEUED); return null;
        }
        if(job.getKind()==Contact.Kind.invitation && !tope.tomar()) {
            job.state(ContactJob.State.QUEUED); return null;
        }
        job.state(ContactJob.State.SENDING);
        return new Dispatch(id,new AuthClient.PedidoDeCodigo(debtor.getRut(),List.of("correo"),debtor.getEmail(),
                debt.getCreditor().getTradeName(),debt.getExternalId(),
                job.getInstallment()==null?null:job.getInstallment().getDueDate()));
    }
    @Transactional
    public void accepted(Long id) { accepted(id, null); }
    @Transactional
    public void accepted(Long id, String destination) {
        ContactJob job=java.util.Objects.requireNonNull(lockJob(id));
        if(job.getState()!=ContactJob.State.SENDING) return;
        Debtor debtor=debtors.locked(job.getDebt().getDebtor().getId()).orElseThrow();
        contacts.save(job.contact(destination == null ? AccesoService.enmascarar(debtor.getEmail()) : destination));
        if (ContactOutbox.hash(debtor.getEmail()).equals(job.getDestinationHash())) debtor.correoSalio();
        job.state(ContactJob.State.ACCEPTED);
    }
    @Transactional
    public void failed(Long id, boolean address, boolean uncertain) {
        ContactJob job=java.util.Objects.requireNonNull(lockJob(id));
        if(job.getState()!=ContactJob.State.SENDING) return;
        Debtor debtor=debtors.locked(job.getDebt().getDebtor().getId()).orElseThrow();
        if (ContactOutbox.hash(debtor.getEmail()).equals(job.getDestinationHash())) {
            debtor.correoNoSalio(uncertain?"Resultado de envio incierto; requiere conciliacion":"El proveedor no acepto el correo",
                    address,Instant.now());
        }
        job.state(uncertain?ContactJob.State.UNCERTAIN:ContactJob.State.FAILED);
    }
}
