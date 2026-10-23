package com.tbridge.auth.service;

import com.tbridge.auth.model.Session;
import com.tbridge.auth.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Las reglas de la sesion revocable.
 *
 * Cada prueba es una de las reglas de V2__sesiones.sql. Si alguna se rompe,
 * la sesion deja de poder cerrarse, o de detectar un robo, o de soltar a quien
 * tenia dos pestanas abiertas.
 */
class SessionServiceTest {

    /** Un reloj que se puede adelantar a mano. */
    static final class RelojManual extends Clock {
        private Instant ahora = Instant.parse("2026-09-23T12:00:00Z");

        void avanzar(Duration cuanto) { ahora = ahora.plus(cuanto); }
        @Override public Instant instant() { return ahora; }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zona) { return this; }
    }

    private final SesionesEnMemoria base = new SesionesEnMemoria();
    private final RelojManual reloj = new RelojManual();
    private final SessionService sesiones = new SessionService(base.repositorio, Duration.ofDays(7), reloj);

    private SessionService.Llave entrar() {
        return sesiones.abrir(Session.Role.DEBTOR, "16482337-7", null, null);
    }

    private static HttpStatus estado(Runnable accion) {
        return assertThrows(ApiException.class, accion::run).getStatus();
    }

    @Test
    void la_llave_no_queda_escrita_en_la_base() {
        SessionService.Llave llave = entrar();

        Session fila = base.filas.get(0);
        assertNotEquals(llave.valor(), fila.getRefreshHash());
        assertEquals(64, fila.getRefreshHash().length());
        assertTrue(base.filas.stream().noneMatch(s -> llave.valor().equals(s.getRefreshHash())));
    }

    @Test
    void renovar_entrega_otra_llave_y_jubila_la_anterior() {
        SessionService.Llave primera = entrar();

        SessionService.Renovada renovada = sesiones.renovar(primera.valor(), null);

        assertNotEquals(primera.valor(), renovada.llave().valor());
        assertNotNull(base.filas.get(0).getRotatedAt());
        assertNull(base.filas.get(1).getRotatedAt());
        assertEquals("16482337-7", renovada.rut());
    }

    @Test
    void renovar_no_alarga_la_vida_de_la_sesion() {
        SessionService.Llave primera = entrar();
        reloj.avanzar(Duration.ofDays(3));

        SessionService.Renovada renovada = sesiones.renovar(primera.valor(), null);

        //  La hija vence cuando vencia la madre, no siete dias despues de hoy.
        assertEquals(primera.venceEn(), renovada.llave().venceEn());
    }

    @Test
    void una_llave_jubilada_que_vuelve_revoca_la_familia_entera() {
        SessionService.Llave robada = entrar();
        SessionService.Renovada delDueno = sesiones.renovar(robada.valor(), null);
        reloj.avanzar(Duration.ofMinutes(5));

        //  El ladron usa la llave que el dueno ya cambio.
        assertEquals(HttpStatus.UNAUTHORIZED, estado(() -> sesiones.renovar(robada.valor(), null)));

        //  Y el dueno tambien queda afuera: no hay forma de saber cual de los
        //  dos es el legitimo, asi que se corta a ambos.
        assertEquals(HttpStatus.UNAUTHORIZED, estado(() -> sesiones.renovar(delDueno.llave().valor(), null)));
        assertTrue(base.filas.stream().allMatch(Session::revocada));
    }

    @Test
    void dos_pestanas_renovando_a_la_vez_no_cierran_la_sesion() {
        SessionService.Llave compartida = entrar();
        SessionService.Renovada primeraPestana = sesiones.renovar(compartida.valor(), null);
        reloj.avanzar(Duration.ofSeconds(2));

        //  La segunda pestana llega con la llave que la primera acaba de cambiar.
        assertEquals(HttpStatus.CONFLICT, estado(() -> sesiones.renovar(compartida.valor(), null)));

        //  No se revoco nada: al reintentar con la llave nueva, sigue adentro.
        assertTrue(base.filas.stream().noneMatch(Session::revocada));
        assertNotNull(sesiones.renovar(primeraPestana.llave().valor(), null));
    }

    @Test
    void pasada_la_gracia_ya_no_es_otra_pestana_sino_un_robo() {
        SessionService.Llave llave = entrar();
        sesiones.renovar(llave.valor(), null);
        reloj.avanzar(SessionService.GRACIA);

        assertEquals(HttpStatus.UNAUTHORIZED, estado(() -> sesiones.renovar(llave.valor(), null)));
        assertTrue(base.filas.stream().allMatch(Session::revocada));
    }

    @Test
    void una_llave_vencida_no_renueva() {
        SessionService.Llave llave = entrar();
        reloj.avanzar(Duration.ofDays(7));

        assertEquals(HttpStatus.UNAUTHORIZED, estado(() -> sesiones.renovar(llave.valor(), null)));
    }

    @Test
    void cerrar_sesion_la_cierra_en_el_servidor() {
        SessionService.Llave llave = entrar();
        SessionService.Renovada renovada = sesiones.renovar(llave.valor(), null);

        sesiones.cerrar(renovada.llave().valor());

        assertEquals(HttpStatus.UNAUTHORIZED, estado(() -> sesiones.renovar(renovada.llave().valor(), null)));
        assertTrue(base.filas.stream().allMatch(Session::revocada));
    }

    @Test
    void cerrar_una_sesion_no_toca_las_de_otra_persona() {
        SessionService.Llave mia = entrar();
        SessionService.Llave ajena = sesiones.abrir(Session.Role.DEBTOR, "18905214-6", null, null);

        sesiones.cerrar(mia.valor());

        assertNotNull(sesiones.renovar(ajena.valor(), null));
    }

    @Test
    void quitarle_el_acceso_a_una_persona_cierra_todas_sus_sesiones_y_ninguna_ajena() {
        //  La misma persona en dos navegadores: dos familias.
        SessionService.Llave enLaOficina = sesiones.abrir(Session.Role.CREDITOR, null, "ana.soto@empresatest.cl", null);
        SessionService.Llave enLaCasa = sesiones.abrir(Session.Role.CREDITOR, null, "ana.soto@empresatest.cl", null);
        SessionService.Llave deOtra = sesiones.abrir(Session.Role.CREDITOR, null, "camila.reyes@apofyx.cl", null);
        SessionService.Llave deUnDeudor = entrar();

        assertEquals(2, sesiones.revocarDe("Ana.Soto@empresatest.cl"));

        assertEquals(HttpStatus.UNAUTHORIZED, estado(() -> sesiones.renovar(enLaOficina.valor(), null)));
        assertEquals(HttpStatus.UNAUTHORIZED, estado(() -> sesiones.renovar(enLaCasa.valor(), null)));
        assertNotNull(sesiones.renovar(deOtra.valor(), null));
        assertNotNull(sesiones.renovar(deUnDeudor.valor(), null));
    }

    @Test
    void no_existe_vencida_y_revocada_responden_lo_mismo() {
        //  Si los mensajes fueran distintos, quien prueba llaves sabria cuales
        //  de sus intentos existieron alguna vez.
        String noExiste = assertThrows(ApiException.class,
                () -> sesiones.renovar("una-llave-inventada", null)).getMessage();

        SessionService.Llave vencida = entrar();
        reloj.avanzar(Duration.ofDays(8));
        String deVencida = assertThrows(ApiException.class,
                () -> sesiones.renovar(vencida.valor(), null)).getMessage();

        SessionService.Llave cerrada = entrar();
        sesiones.cerrar(cerrada.valor());
        String deCerrada = assertThrows(ApiException.class,
                () -> sesiones.renovar(cerrada.valor(), null)).getMessage();

        assertEquals(noExiste, deVencida);
        assertEquals(noExiste, deCerrada);
    }

    @Test
    void cada_llave_es_distinta() {
        SessionService.Llave una = entrar();
        SessionService.Llave otra = entrar();
        assertNotEquals(una.valor(), otra.valor());
        assertFalse(una.valor().isBlank());
    }
}
