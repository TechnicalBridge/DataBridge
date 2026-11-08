package com.tbridge.debt.service;
import com.tbridge.debt.client.*;
import com.tbridge.debt.model.*;
import com.tbridge.debt.repository.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContactDispatcherTest {
    ContactJobRepository jobs=mock(ContactJobRepository.class);
    ContactDispatchTransactions tx=mock(ContactDispatchTransactions.class);
    AuthClient auth=mock(AuthClient.class);
    ContactDispatcher worker=new ContactDispatcher(jobs,tx,auth);
    @AfterEach void close(){worker.close();}
    private ContactDispatchTransactions.Dispatch work(Long id){return new ContactDispatchTransactions.Dispatch(id,
            new AuthClient.PedidoDeCodigo("fixture",List.of("correo"),"fixture@example.com","fixture","fixture"));}
    @Test void no_work_means_no_remote_call(){worker.dispatch(1L);verifyNoInteractions(auth);}
    @Test void accepted_only_after_provider_returns(){when(tx.claim(1L)).thenReturn(work(1L));worker.dispatch(1L);
        var order=inOrder(tx,auth);order.verify(tx).claim(1L);order.verify(auth).emitirCodigo(any());order.verify(tx).accepted(eq(1L),anyString());}
    @Test void permanent_recipient_failure_is_not_a_contact(){when(tx.claim(1L)).thenReturn(work(1L));
        when(auth.emitirCodigo(any())).thenThrow(new CorreoNoSalio("fixture",true));worker.dispatch(1L);
        verify(tx).failed(1L,true,false);verify(tx,never()).accepted(any(),any());}
    @Test void exhausted_provider_failure_is_durable_failed(){when(tx.claim(1L)).thenReturn(work(1L));
        when(auth.emitirCodigo(any())).thenThrow(new CorreoNoSalio("fixture",false));worker.dispatch(1L);
        verify(tx).failed(1L,false,false);verify(tx,never()).accepted(any(),any());}
    @Test void ambiguous_transport_is_never_automatically_resent(){when(tx.claim(1L)).thenReturn(work(1L));
        when(auth.emitirCodigo(any())).thenThrow(new IllegalStateException("fixture"));worker.dispatch(1L);
        verify(tx).failed(1L,false,true);verify(tx,never()).accepted(any(),any());}
    @Test void rejects_transaction_wrapping_http(){TransactionSynchronizationManager.setActualTransactionActive(true);
        try{assertThrows(IllegalStateException.class,()->worker.dispatch(1L));verifyNoInteractions(auth);}
        finally{TransactionSynchronizationManager.setActualTransactionActive(false);}}
    @Test void only_four_jobs_are_active_and_memory_queue_cannot_grow() throws Exception {
        var entered=new CountDownLatch(4);var release=new CountDownLatch(1);
        when(jobs.queued(any())).thenReturn(List.of(1L,2L,3L,4L));
        when(tx.claim(any())).thenAnswer(i->work(i.getArgument(0)));
        when(auth.emitirCodigo(any())).thenAnswer(i->{entered.countDown();release.await(5,TimeUnit.SECONDS);return null;});
        try{worker.poll();assertTrue(entered.await(5,TimeUnit.SECONDS));for(int n=0;n<10;n++)worker.poll();
            verify(jobs,times(1)).queued(any());verify(auth,times(4)).emitirCodigo(any());}
        finally{release.countDown();}
    }
}
