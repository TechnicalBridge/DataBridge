package com.tbridge.debt.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.debt.repository.*;
import com.tbridge.debt.model.Campaign;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ContactoSchedulerTransactionTest {
    @Test void scheduler_self_invocation_keeps_the_local_selection_transaction(){
        var campaigns=mock(CampaignRepository.class);var invitations=mock(InvitacionService.class);
        var horario=mock(HorarioDeContacto.class);when(horario.permite(any())).thenReturn(true);
        when(invitations.invitarPendientes(any(),any())).thenReturn(new InvitacionService.Pasada(0,false));
        when(campaigns.findByStatus(Campaign.Status.running)).thenAnswer(i->{
            assertTrue(TransactionSynchronizationManager.isActualTransactionActive());return java.util.List.of();});
        var service=new ContactoService(campaigns,mock(DebtRepository.class),mock(ContactRepository.class),
                mock(ContactOutbox.class),mock(LimiteDeContacto.class),invitations,new ObjectMapper(),horario);
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:scheduler-transaction");
        var proxy=new ProxyFactory(service);
        proxy.addAdvice(new TransactionInterceptor(new DataSourceTransactionManager(source),new AnnotationTransactionAttributeSource()));
        ((ContactoService)proxy.getProxy()).pasar();
        verify(campaigns).findByStatus(Campaign.Status.running);
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }
}
