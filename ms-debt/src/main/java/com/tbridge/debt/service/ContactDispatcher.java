package com.tbridge.debt.service;

import com.tbridge.debt.client.AuthClient;
import com.tbridge.debt.client.AuthNoContactado;
import com.tbridge.debt.client.CorreoNoSalio;
import com.tbridge.debt.repository.ContactJobRepository;
import java.util.concurrent.*;
import jakarta.annotation.PreDestroy;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Cuatro envios por instancia; las otras intenciones esperan en DB, no en una cola de memoria. */
@Service
public class ContactDispatcher {
    private final ThreadPoolExecutor executor = new ThreadPoolExecutor(4,4,0,TimeUnit.SECONDS,
            new SynchronousQueue<>(), new ThreadPoolExecutor.AbortPolicy());
    private final Semaphore slots = new Semaphore(4);
    private final ContactJobRepository jobs;
    private final ContactDispatchTransactions transactions;
    private final AuthClient auth;
    public ContactDispatcher(ContactJobRepository jobs, ContactDispatchTransactions transactions, AuthClient auth) {
        this.jobs=jobs; this.transactions=transactions; this.auth=auth;
    }
    @Scheduled(fixedDelay=1000, initialDelay=5000)
    public void poll() {
        transactions.recover();
        int free=slots.availablePermits();
        if(free==0) return;
        for(Long id:jobs.queued(java.time.Instant.now(), PageRequest.of(0,free))) {
            if(!slots.tryAcquire()) break;
            try { executor.execute(() -> { try { dispatch(id); } finally { slots.release(); } }); }
            catch(RejectedExecutionException full) { slots.release(); break; }
        }
    }
    public void dispatch(Long id) {
        if(TransactionSynchronizationManager.isActualTransactionActive())
            throw new IllegalStateException("El despacho HTTP no puede ejecutarse en una transaccion");
        ContactDispatchTransactions.Dispatch work=transactions.claim(id);
        if(work==null) return;
        try {
            auth.emitirCodigo(work.request());
        } catch(AuthNoContactado notSent) {
            boolean scheduled = transactions.retryBeforeSend(id);
            LoggerFactory.getLogger(getClass()).warn("job_id={} outcome={}", id,
                    scheduled ? "retry_scheduled" : "retry_exhausted");
            return;
        } catch(CorreoNoSalio rejected) {
            transactions.failed(id,rejected.deLaDireccion(),false);
            LoggerFactory.getLogger(getClass()).warn("job_id={} outcome=failed",id);
            return;
        } catch(RuntimeException ambiguous) {
            transactions.failed(id,false,true);
            LoggerFactory.getLogger(getClass()).warn("job_id={} outcome=uncertain",id);
            return;
        }
        // Si este commit falla, SENDING pasa a UNCERTAIN, nunca se vuelve a enviar automaticamente.
        transactions.accepted(id, AccesoService.enmascarar(work.request().correo()));
        LoggerFactory.getLogger(getClass()).info("job_id={} outcome=accepted",id);
    }
    @PreDestroy public void close() { executor.shutdown(); }
}
