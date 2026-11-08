package com.tbridge.debt.service;

import com.tbridge.debt.model.*;
import com.tbridge.debt.repository.*;
import com.tbridge.debt.client.AuthClient;
import jakarta.persistence.EntityManager;
import java.time.*;
import java.math.BigDecimal;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.context.annotation.*;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.transaction.*;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.support.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@DataJpaTest(showSql=false,properties={"spring.flyway.enabled=false","spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.datasource.url=jdbc:h2:mem:outbox;MODE=MySQL;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000",
        "spring.datasource.username=sa","spring.datasource.password=","spring.jpa.show-sql=false"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes=ContactOutboxIntegrationTest.Config.class)
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class ContactOutboxIntegrationTest {
    @Configuration @EntityScan("com.tbridge.debt.model") @EnableJpaRepositories("com.tbridge.debt.repository")
    @Import({ContactOutbox.class,ContactDispatchTransactions.class,LimiteDeContacto.class})
    static class Config {
        @Bean com.tbridge.debt.config.Cifrado cifrado(){return new com.tbridge.debt.config.Cifrado("fixture-only-not-production");}
        @Bean HorarioDeContacto horario(){var h=mock(HorarioDeContacto.class);when(h.permiteAhora()).thenReturn(true);when(h.permite(any())).thenReturn(true);return h;}
        @Bean TopeDeInvitaciones tope(){return new TopeDeInvitaciones(0);}
    }
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager manager;
    @Autowired ContactOutbox outbox;
    @Autowired ContactJobRepository jobs;
    @Autowired ContactRepository contacts;
    @Autowired DebtRepository debts;
    @Autowired ContactDispatchTransactions dispatchTx;
    private TransactionTemplate tx;
    @BeforeEach void setup(){tx=new TransactionTemplate(manager);}
    private Long debt(){
        return tx.execute(status->{
            String suffix=UUID.randomUUID().toString().substring(0,8);
            Organization org=new Organization();org.setRut(suffix+"-0");org.setLegalName("Fixture");org.setTradeName("Fixture");em.persist(org);
            Batch batch=new Batch();batch.setSender(org);batch.setCreditor(org);batch.setExternalId(suffix);batch.setCutOff(LocalDate.now());
            batch.setPayloadHash("0".repeat(64));em.persist(batch);
            Debtor debtor=new Debtor();debtor.setRut(suffix+"-1");debtor.setFullName("Fixture");debtor.setEmail("fixture@example.com");em.persist(debtor);
            Debt debt=new Debt();debt.setCreditor(org);debt.setDebtor(debtor);debt.setFirstBatch(batch);debt.setLastBatch(batch);
            debt.setExternalId(suffix);debt.setConcept("Fixture");debt.setOriginalAmount(BigDecimal.TEN);em.persist(debt);em.flush();return debt.getId();
        });
    }
    private boolean enqueue(Long id){return tx.execute(status->outbox.enqueue(Contact.invitacion(debts.findById(id).orElseThrow(),"***")));}
    private Long job(Long debt){return tx.execute(status->jobs.findByJobKey(ContactJob.key(Contact.invitacion(debts.findById(debt).orElseThrow(),"***"))).orElseThrow().getId());}
    @Test void two_concurrent_schedulers_create_one_durable_intent() throws Exception {
        Long id=debt();var start=new CountDownLatch(1);List<Future<Boolean>> futures=new ArrayList<>();
        try(var executor=Executors.newFixedThreadPool(2)){
            for(int n=0;n<2;n++)futures.add(executor.submit(()->{start.await();return enqueue(id);}));start.countDown();
            int accepted=0;for(var future:futures)if(future.get(15,TimeUnit.SECONDS))accepted++;
            assertEquals(1,accepted);
        }
        assertNotNull(job(id));
        assertEquals(Boolean.TRUE,tx.execute(s->contacts.findByDebtOrderByOccurredAtAsc(debts.findById(id).orElseThrow()).isEmpty()));
    }
    @Test void rollback_leaves_no_job_and_acceptance_creates_exactly_one_contact(){
        Long id=debt();
        tx.executeWithoutResult(status->{outbox.enqueue(Contact.invitacion(debts.findById(id).orElseThrow(),"***"));status.setRollbackOnly();});
        assertTrue(enqueue(id));Long job=job(id);
        assertNotNull(dispatchTx.claim(job));assertNull(dispatchTx.claim(job));
        dispatchTx.accepted(job);dispatchTx.accepted(job);
        assertEquals(1,tx.execute(s->contacts.findByDebtOrderByOccurredAtAsc(debts.findById(id).orElseThrow()).size()).intValue());
        assertEquals(ContactJob.State.ACCEPTED,jobs.findById(job).orElseThrow().getState());
    }
    @Test void provider_wait_occurs_after_commit_and_batch_selection_does_not_wait_linearly() throws Exception {
        List<Long> ids=new ArrayList<>();for(int n=0;n<10;n++)ids.add(debt());
        long before=System.nanoTime();for(Long id:ids)assertTrue(enqueue(id));
        long queueMs=Duration.ofNanos(System.nanoTime()-before).toMillis();
        AuthClient fake=mock(AuthClient.class);var outside=new java.util.concurrent.atomic.AtomicBoolean(true);
        doAnswer(i->{outside.compareAndSet(true,!TransactionSynchronizationManager.isActualTransactionActive());Thread.sleep(500);return null;})
                .when(fake).emitirCodigo(any());
        List<Long> jobIds=ids.stream().map(this::job).toList();long sending=System.nanoTime();
        try(var dispatcher=new AutoClosingDispatcher(jobs,dispatchTx,fake)){
            for(Long job:jobIds)dispatcher.delegate.dispatch(job);
        }
        long dispatchMs=Duration.ofNanos(System.nanoTime()-sending).toMillis();
        assertTrue(outside.get());assertTrue(queueMs<dispatchMs,"queue="+queueMs+", dispatch="+dispatchMs);
        assertTrue(dispatchMs>=5000);verify(fake,times(10)).emitirCodigo(any());
        System.out.println("OUTBOX_BENCHMARK messages=10 fake_delay_ms=500 queue_ms="+queueMs+" drain_serial_ms="+dispatchMs);
    }

    @Test void cancelled_intent_can_resume_when_selector_finds_it_eligible_again(){
        Long id=debt();assertTrue(enqueue(id));Long job=job(id);
        tx.executeWithoutResult(status->jobs.findById(job).orElseThrow().state(ContactJob.State.CANCELLED));
        assertTrue(enqueue(id));assertEquals(ContactJob.State.QUEUED,jobs.findById(job).orElseThrow().getState());
    }
    @Test void known_failure_is_not_retried_forever_but_address_correction_can_recover(){
        Long id=debt();assertTrue(enqueue(id));Long job=job(id);
        tx.executeWithoutResult(status->jobs.findById(job).orElseThrow().state(ContactJob.State.FAILED));
        assertFalse(enqueue(id));
        tx.executeWithoutResult(status->debts.findById(id).orElseThrow().getDebtor().setEmail("corrected@example.com"));
        assertTrue(enqueue(id));assertEquals(ContactJob.State.QUEUED,jobs.findById(job).orElseThrow().getState());
    }
    private static class AutoClosingDispatcher implements AutoCloseable {
        final ContactDispatcher delegate;
        AutoClosingDispatcher(ContactJobRepository jobs,ContactDispatchTransactions tx,AuthClient auth){delegate=new ContactDispatcher(jobs,tx,auth);}
        public void close(){delegate.close();}
    }
}
