package com.tbridge.debt.service;
import com.tbridge.debt.model.*;
import com.tbridge.debt.repository.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContactDispatchTransactionsTest {
    ContactJobRepository jobs=mock(ContactJobRepository.class);
    DebtorRepository debtors=mock(DebtorRepository.class);
    ContactRepository contacts=mock(ContactRepository.class);
    HorarioDeContacto horario=mock(HorarioDeContacto.class);
    LimiteDeContacto limite=mock(LimiteDeContacto.class);
    TopeDeInvitaciones tope=mock(TopeDeInvitaciones.class);
    ContactDispatchTransactions tx=new ContactDispatchTransactions(jobs,debtors,contacts,horario,limite,tope);
    Debtor debtor=new Debtor();Debt debt=new Debt();ContactJob job;
    @BeforeEach void setup(){
        debtor.setId(2L);debtor.setRut("fixture");debtor.setEmail("fixture@example.com");
        Organization org=new Organization();org.setTradeName("Fixture");
        debt.setId(3L);debt.setDebtor(debtor);debt.setCreditor(org);debt.setExternalId("fixture");
        use(Contact.invitacion(debt,"***"));
        when(jobs.debtorId(1L)).thenReturn(Optional.of(2L));when(debtors.locked(2L)).thenReturn(Optional.of(debtor));
        when(horario.permiteAhora()).thenReturn(true);when(horario.permite(any())).thenReturn(true);
        when(limite.permiteSinReserva(any(),any())).thenReturn(true);when(tope.tomar()).thenReturn(true);
    }
    void use(Contact contact){job=new ContactJob(contact,ContactOutbox.hash(debtor.getEmail()));when(jobs.locked(1L)).thenReturn(Optional.of(job));}
    @Test void valid_invitation_claims_once(){assertNotNull(tx.claim(1L));assertNull(tx.claim(1L));assertEquals(ContactJob.State.SENDING,job.getState());verify(tope,times(1)).tomar();}
    @Test void outside_legal_window_stays_queued(){when(horario.permiteAhora()).thenReturn(false);assertNull(tx.claim(1L));assertEquals(ContactJob.State.QUEUED,job.getState());verify(tope,never()).tomar();}
    @Test void near_window_end_stays_queued(){when(horario.permite(any())).thenReturn(false);assertNull(tx.claim(1L));assertEquals(ContactJob.State.QUEUED,job.getState());}
    @Test void exhausted_legal_quota_is_rechecked_at_dispatch(){when(limite.permiteSinReserva(any(),any())).thenReturn(false);assertNull(tx.claim(1L));assertEquals(ContactJob.State.QUEUED,job.getState());}
    @Test void invitation_cap_is_consumed_at_dispatch(){when(tope.tomar()).thenReturn(false);assertNull(tx.claim(1L));assertEquals(ContactJob.State.QUEUED,job.getState());}
    @ParameterizedTest @EnumSource(value=Debt.Status.class,names={"paid","withdrawn","disputed","repacted"})
    void no_invitation_after_state_changes(Debt.Status state){debt.setStatus(state);assertNull(tx.claim(1L));assertEquals(ContactJob.State.CANCELLED,job.getState());}
    @Test void suspended_creditor_cancels(){debt.getCreditor().setStatus(Organization.Status.suspended);assertNull(tx.claim(1L));assertEquals(ContactJob.State.CANCELLED,job.getState());}
    @Test void changed_destination_never_sends_to_a_stale_address(){debtor.setEmail("changed@example.com");assertNull(tx.claim(1L));assertEquals(ContactJob.State.CANCELLED,job.getState());}
    @Test void recipient_failure_records_problem_without_contact(){job.state(ContactJob.State.SENDING);tx.failed(1L,true,false);assertEquals(1,debtor.getEmailRejections());assertEquals(ContactJob.State.FAILED,job.getState());verifyNoInteractions(contacts);}
    @Test void provider_failure_does_not_count_as_recipient_rejection(){job.state(ContactJob.State.SENDING);tx.failed(1L,false,false);assertEquals(0,debtor.getEmailRejections());verifyNoInteractions(contacts);}
    @Test void uncertain_result_reserves_identity_without_contact(){job.state(ContactJob.State.SENDING);tx.failed(1L,false,true);assertEquals(ContactJob.State.UNCERTAIN,job.getState());assertTrue(ContactOutbox.RESERVED.contains(job.getState()));verifyNoInteractions(contacts);}
    @Test void reminder_opt_out_is_rechecked(){Installment installment=new Installment();installment.setDebt(debt);installment.setDueDate(LocalDate.now().plusDays(2));use(Contact.recordatorio(installment,"***"));debtor.setReminders(false);assertNull(tx.claim(1L));assertEquals(ContactJob.State.CANCELLED,job.getState());}
    @Test void paused_campaign_is_rechecked(){Campaign c=new Campaign();c.setStartsOn(LocalDate.now().minusDays(1));c.setId(10L);c.setStatus(Campaign.Status.paused);debt.setCampaign(c);use(Contact.toque(debt,c,1,"***"));assertNull(tx.claim(1L));assertEquals(ContactJob.State.CANCELLED,job.getState());}
    @Test void manual_resend_may_retry_a_problem_address(){for(int n=0;n<3;n++)debtor.correoNoSalio("fixture",true,Instant.now());use(Contact.reenvio(debt,"***"));assertNotNull(tx.claim(1L));assertEquals(3,debtor.getEmailRejections());}
    @Test void failure_of_old_destination_does_not_mark_the_new_address_invalid(){
        job.state(ContactJob.State.SENDING);debtor.setEmail("corrected@example.com");tx.failed(1L,true,false);
        assertEquals(0,debtor.getEmailRejections());assertNull(debtor.getEmailFailure());
    }

    private Campaign campaign(long id) {
        Campaign campaign=new Campaign();campaign.setId(id);campaign.setStartsOn(LocalDate.now().minusDays(1));
        campaign.setChannels("[\"correo\"]");return campaign;
    }
    @Test void reassigned_debt_cancels_the_old_campaign_touch(){
        Campaign old=campaign(10L);use(Contact.toque(debt,old,1,"***"));debt.setCampaign(campaign(20L));
        assertNull(tx.claim(1L));assertEquals(ContactJob.State.CANCELLED,job.getState());
        assertEquals(0,job.getDispatchAttempts());verifyNoInteractions(contacts);
    }
    @Test void removed_campaign_cancels_the_pending_touch(){
        use(Contact.toque(debt,campaign(10L),1,"***"));debt.setCampaign(null);
        assertNull(tx.claim(1L));assertEquals(ContactJob.State.CANCELLED,job.getState());
    }
    @Test void matching_campaign_ids_allow_dispatch_even_with_different_entity_instances(){
        use(Contact.toque(debt,campaign(10L),1,"***"));debt.setCampaign(campaign(10L));
        assertNotNull(tx.claim(1L));assertEquals(ContactJob.State.SENDING,job.getState());
    }
    @Test void safe_connection_retries_have_a_deadline_and_a_finite_budget(){
        Instant now=Instant.parse("2026-11-08T12:00:00Z");
        job.startAttempt();assertTrue(job.retryBeforeSend(now));
        assertEquals(now.plusSeconds(30),job.getNextAttemptAt());assertFalse(job.ready(now.plusSeconds(29)));
        assertTrue(job.ready(now.plusSeconds(30)));
        job.startAttempt();assertTrue(job.retryBeforeSend(now));assertEquals(now.plusSeconds(60),job.getNextAttemptAt());
        job.startAttempt();assertFalse(job.retryBeforeSend(now));assertEquals(ContactJob.State.FAILED,job.getState());
        assertEquals(3,job.getDispatchAttempts());assertFalse(ContactOutbox.RESERVED.contains(job.getState()));
    }
    @Test void deferred_job_is_not_claimed_early_and_does_not_consume_invitation_quota(){
        assertNotNull(tx.claim(1L));assertTrue(tx.retryBeforeSend(1L));assertNull(tx.claim(1L));
        assertEquals(1,job.getDispatchAttempts());verify(tope,times(1)).tomar();verifyNoInteractions(contacts);
    }

}
