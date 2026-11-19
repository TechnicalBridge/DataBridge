package com.tbridge.payments.model;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DebtNotificationTest {
    @Test
    void sexto_fallo_programa_24_horas_y_septimo_se_rinde() {
        DebtNotification aviso = DebtNotification.para(12L);
        for (int i = 0; i < 5; i++) {
            aviso.fallo("sin ms-debt");
            assertEquals(DebtNotification.Status.pending, aviso.getStatus());
        }
        Instant antes = Instant.now();
        aviso.fallo("sin ms-debt");
        assertEquals(DebtNotification.Status.pending, aviso.getStatus());
        assertTrue(!aviso.getNextAttemptAt().isBefore(antes.plus(Duration.ofHours(24))));
        assertTrue(aviso.getNextAttemptAt().isBefore(Instant.now().plus(Duration.ofHours(24)).plusSeconds(2)));
        aviso.fallo("sin ms-debt");
        assertEquals(DebtNotification.Status.failed, aviso.getStatus());
        assertEquals((short) 7, aviso.getAttempts());
    }
}
