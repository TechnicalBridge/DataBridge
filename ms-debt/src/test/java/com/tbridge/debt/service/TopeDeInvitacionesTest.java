package com.tbridge.debt.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cuantas invitaciones caben en cada cuarto de hora (#236). */
class TopeDeInvitacionesTest {

    private static Clock a(String instante) {
        return Clock.fixed(Instant.parse(instante), ZoneOffset.UTC);
    }

    @Test
    void caben_las_del_tope_y_ninguna_mas_en_el_mismo_cuarto_de_hora() {
        TopeDeInvitaciones tope = new TopeDeInvitaciones(2, a("2026-10-23T13:01:00Z"));
        assertTrue(tope.tomar());
        assertTrue(tope.tomar());
        assertFalse(tope.tomar());
    }

    @Test
    void el_cuarto_de_hora_siguiente_vuelve_a_tener_cupo() {
        Instant ahora = Instant.parse("2026-10-23T13:14:00Z");
        Clock[] reloj = {Clock.fixed(ahora, ZoneOffset.UTC)};
        TopeDeInvitaciones tope = new TopeDeInvitaciones(1, new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zona) { return this; }
            @Override public Instant instant() { return reloj[0].instant(); }
        });
        assertTrue(tope.tomar());
        assertFalse(tope.tomar());
        reloj[0] = Clock.fixed(ahora.plus(2, ChronoUnit.MINUTES), ZoneOffset.UTC);
        assertTrue(tope.tomar(), "a las 13:16 empieza otro cuarto de hora");
    }

    @Test
    void con_cero_no_hay_tope() {
        TopeDeInvitaciones tope = new TopeDeInvitaciones(0, a("2026-10-23T13:01:00Z"));
        for (int i = 0; i < 1000; i++) {
            assertTrue(tope.tomar());
        }
    }
}
