package com.tbridge.debt.service;

import org.junit.jupiter.api.Test;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertFalse;

class CodigosSoloSolicitadosTest {

    @Test
    void las_campanas_no_reenvian_codigos_por_un_reloj() {
        sinProgramacion(ContactoService.class);
    }

    @Test
    void los_recordatorios_no_emiten_codigos_automaticos() {
        sinProgramacion(RecordatorioService.class);
    }

    @Test
    void confirmar_una_cartera_no_dispara_un_listener_de_invitaciones() {
        for (var metodo : InvitacionService.class.getDeclaredMethods()) {
            assertFalse(metodo.isAnnotationPresent(TransactionalEventListener.class));
            assertFalse(metodo.isAnnotationPresent(EventListener.class));
        }
    }

    private static void sinProgramacion(Class<?> tipo) {
        assertFalse(tipo.isAnnotationPresent(Scheduled.class));
        assertFalse(Arrays.stream(tipo.getDeclaredMethods())
                .anyMatch(m -> m.getAnnotationsByType(Scheduled.class).length > 0));
    }
}
