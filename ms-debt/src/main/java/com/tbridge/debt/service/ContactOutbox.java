package com.tbridge.debt.service;

import com.tbridge.debt.model.*;
import com.tbridge.debt.repository.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

@Service
public class ContactOutbox {
    public static final List<ContactJob.State> RESERVED = List.of(ContactJob.State.QUEUED,
            ContactJob.State.SENDING, ContactJob.State.UNCERTAIN);
    private final ContactJobRepository jobs;
    private final DebtorRepository debtors;
    private final LimiteDeContacto limite;
    public ContactOutbox(ContactJobRepository jobs, DebtorRepository debtors, LimiteDeContacto limite) {
        this.jobs=jobs; this.debtors=debtors; this.limite=limite;
    }
    @Transactional(propagation=Propagation.MANDATORY)
    public boolean enqueue(Contact contact) {
        Debtor debtor=contact.getDebt().getDebtor();
        debtors.locked(debtor.getId()).orElseThrow();
        if(!limite.permiteSinReserva(debtor, java.time.Instant.now())) return false;
        ContactJob previous = jobs.findByJobKey(ContactJob.key(contact)).orElse(null);
        if (!jobs.reserved(debtor.getId(), RESERVED).isEmpty()) return false;
        String destinationHash = hash(debtor.getEmail());
        if (previous != null) {
            if (previous.getState() == ContactJob.State.CANCELLED
                    || (previous.getState() == ContactJob.State.FAILED
                        && !destinationHash.equals(previous.getDestinationHash()))) {
                previous.correctedDestination(destinationHash);
                return true;
            }
            return false;
        }
        jobs.save(new ContactJob(contact, destinationHash));
        return true;
    }
    static String hash(String email) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest((email==null?"":email.strip().toLowerCase(java.util.Locale.ROOT)).getBytes(StandardCharsets.UTF_8))); }
        catch(java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
