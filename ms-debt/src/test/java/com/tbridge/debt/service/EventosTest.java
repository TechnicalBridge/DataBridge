package com.tbridge.debt.service;

import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.OutboxEvent;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventosTest {

    /**
     * La firma de referencia se calculo con el crypto de Node, que es con lo
     * que Patrimonio verifica. Si Java firmara distinto —otro orden, otra
     * codificacion—, todo evento llegaria rechazado y nadie sabria por que.
     */
    @Test
    void firmaIgualQueElReceptor() {
        String cuerpo = "{\"id\":\"evt_1\",\"tipo\":\"pago.confirmado\"}";
        assertEquals("v1=344ce2850a1c9cb0b60434906199eb2d716de503c6191265746204fcaa31d3f6",
                EventDispatcher.firma("whsec_prueba", 1789923791L, cuerpo));
    }

    @Test
    void otroTimestampEsOtraFirma() {
        String cuerpo = "{\"id\":\"evt_1\"}";
        assertNotEquals(EventDispatcher.firma("s", 1L, cuerpo), EventDispatcher.firma("s", 2L, cuerpo));
    }

    @Test
    void lasFechasVanConElDesfaseDeChile() {
        // El 20 de septiembre Chile ya esta en horario de verano (UTC-3): 17:03 UTC son las 14:03.
        String enChile = EventosService.enChile(Instant.parse("2026-09-20T17:03:11.987Z"));
        assertTrue(enChile.startsWith("2026-09-20T14:03:11"), enChile);
        assertTrue(enChile.endsWith("-03:00"), enChile);
    }

    @Test
    void losPesosViajanEnterosYLaUfConDecimales() {
        assertEquals(520000L, EventosService.monto(new BigDecimal("520000.00"), Debt.Currency.CLP));
        assertEquals(new BigDecimal("38.5"), EventosService.monto(new BigDecimal("38.50"), Debt.Currency.UF));
    }

    @Test
    void espera_24_horas_tras_el_sexto_y_se_rinde_al_septimo() {
        OutboxEvent evento = OutboxEvent.para(null, "x", "pago.confirmado", "{}", Instant.now());
        for (int i = 0; i < 6; i++) {
            Instant antes = Instant.now();
            evento.fallo("caido");
            assertEquals(OutboxEvent.Status.pending, evento.getStatus());
            if (i == 5) {
                assertTrue(!evento.getNextAttemptAt().isBefore(antes.plus(Duration.ofHours(24))));
                assertTrue(evento.getNextAttemptAt().isBefore(Instant.now().plus(Duration.ofHours(24)).plusSeconds(2)));
            }
        }
        evento.fallo("caido");
        assertEquals(OutboxEvent.Status.failed, evento.getStatus());
        assertEquals((short) 7, evento.getAttempts());
    }
}
