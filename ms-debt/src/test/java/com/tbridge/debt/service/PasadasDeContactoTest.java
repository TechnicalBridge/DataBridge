package com.tbridge.debt.service;

import com.tbridge.debt.dto.response.ContactoEstadoResponse;
import com.tbridge.debt.dto.response.ContactosResponse;
import org.hibernate.LazyInitializationException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** La pasada de contacto vista desde el panel (#441): la ultima, la proxima y si se detuvo. */
class PasadasDeContactoTest {

    private static final Instant ARRANQUE = Instant.parse("2026-11-20T13:07:00Z");

    /** Un reloj que se puede mover. */
    private static final class Reloj extends Clock {
        Instant ahora = ARRANQUE;
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zona) { return this; }
        @Override public Instant instant() { return ahora; }
    }

    private final Reloj reloj = new Reloj();
    private final HorarioDeContacto horario = mock(HorarioDeContacto.class);
    private final LimiteDeContacto limite = mock(LimiteDeContacto.class);
    private final PasadasDeContacto pasadas = new PasadasDeContacto("0 */15 * * * *", horario, limite, reloj);

    @Test
    void sin_pasadas_dice_desde_cuando_cuenta_y_cuando_toca_la_proxima() {
        ContactoEstadoResponse estado = pasadas.estado();

        assertEquals(ARRANQUE, estado.desde());
        assertNull(estado.ultima());
        assertNull(estado.enCursoDesde());
        assertEquals(Instant.parse("2026-11-20T13:15:00Z"), estado.proxima());
        assertFalse(estado.atrasada());
    }

    @Test
    void la_ultima_pasada_dice_lo_que_hizo() {
        reloj.ahora = Instant.parse("2026-11-20T13:15:00Z");
        pasadas.empezo();
        assertEquals(reloj.ahora, pasadas.estado().enCursoDesde(), "mientras corre, se ve");
        reloj.ahora = Instant.parse("2026-11-20T13:15:04Z");
        pasadas.termino(new ContactosResponse(3, 2, true, 1));

        ContactoEstadoResponse.Pasada ultima = pasadas.estado().ultima();
        assertEquals(Instant.parse("2026-11-20T13:15:00Z"), ultima.inicio());
        assertEquals(Instant.parse("2026-11-20T13:15:04Z"), ultima.fin());
        assertTrue(ultima.enHorario());
        assertEquals(3, ultima.enviados());
        assertEquals(1, ultima.invitados());
        assertEquals(2, ultima.pendientes());
        assertNull(ultima.error());
        assertNull(pasadas.estado().enCursoDesde());
    }

    @Test
    void una_pasada_que_se_cae_deja_el_tipo_de_error_y_no_su_mensaje() {
        reloj.ahora = Instant.parse("2026-11-20T13:15:00Z");
        pasadas.empezo();
        pasadas.fallo(new LazyInitializationException("could not initialize proxy [Organization#7]"));

        ContactoEstadoResponse.Pasada ultima = pasadas.estado().ultima();
        assertEquals("LazyInitializationException", ultima.error());
        assertEquals(0, ultima.enviados());
    }

    @Test
    void si_no_paso_la_que_tocaba_esta_atrasada() {
        reloj.ahora = Instant.parse("2026-11-20T13:15:00Z");
        pasadas.empezo();
        pasadas.termino(new ContactosResponse(0, 0, true, 0));

        reloj.ahora = Instant.parse("2026-11-20T13:31:00Z");
        assertFalse(pasadas.estado().atrasada(), "la de las 13:30 todavia tiene margen");
        reloj.ahora = Instant.parse("2026-11-20T13:33:00Z");
        assertTrue(pasadas.estado().atrasada(), "la de las 13:30 no paso");
        assertEquals(Instant.parse("2026-11-20T13:45:00Z"), pasadas.estado().proxima());

        pasadas.empezo();
        assertFalse(pasadas.estado().atrasada(), "si esta corriendo, no esta detenida");
    }

    @Test
    void una_pasada_que_parte_unos_milisegundos_antes_de_su_hora_no_queda_atrasada() {
        //  Spring disparo la de las 13:15 a las 13:14:59,960: la siguiente es la de las 13:30, no la misma.
        reloj.ahora = Instant.parse("2026-11-20T13:14:59.960Z");
        pasadas.empezo();
        pasadas.termino(new ContactosResponse(0, 0, true, 0));

        reloj.ahora = Instant.parse("2026-11-20T13:20:00Z");
        assertFalse(pasadas.estado().atrasada());
        reloj.ahora = Instant.parse("2026-11-20T13:33:00Z");
        assertTrue(pasadas.estado().atrasada(), "la de las 13:30 si se la salto");
    }

    @Test
    void dice_si_el_horario_y_el_limite_de_la_ley_estan_activos() {
        assertTrue(pasadas.estado().horarioLegal());
        assertTrue(pasadas.estado().limiteSemanal());

        when(horario.apagado()).thenReturn(true);
        when(limite.apagado()).thenReturn(true);
        assertFalse(pasadas.estado().horarioLegal());
        assertFalse(pasadas.estado().limiteSemanal());
    }
}
