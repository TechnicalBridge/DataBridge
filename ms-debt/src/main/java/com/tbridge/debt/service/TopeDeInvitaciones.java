package com.tbridge.debt.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;

/**
 * Cuantas invitaciones salen en cada cuarto de hora, entre las que salen al
 * cargar una cartera y las de la pasada de contacto.
 *
 * <p>Cada deuda que entra invita a su deudor en segundo plano, asi que una
 * carga de mil deudores mandaba mil correos en el mismo minuto: con un
 * proveedor como Brevo, eso agota su cupo diario de una vez (#236). Lo que
 * pasa del tope no se pierde: queda sin invitar y sale en las pasadas
 * siguientes, que corren cada 15 minutos. Con 0 no hay tope.
 */
@Component
public class TopeDeInvitaciones {

    private static final long CUARTO_DE_HORA = Duration.ofMinutes(15).toMillis();

    private final int maximo;
    private final Clock reloj;
    private long ventana = -1;
    private int usadas;

    @Autowired
    public TopeDeInvitaciones(@Value("${app.contacto.invitaciones-por-cuarto-de-hora:100}") int maximo) {
        this(maximo, Clock.systemUTC());
    }

    TopeDeInvitaciones(int maximo, Clock reloj) {
        this.maximo = Math.max(maximo, 0);
        this.reloj = reloj;
    }

    /** Toma una invitacion de este cuarto de hora. Falso si ya salieron todas las que caben. */
    public synchronized boolean tomar() {
        if (maximo == 0) {
            return true;
        }
        long actual = reloj.millis() / CUARTO_DE_HORA;
        if (actual != ventana) {
            ventana = actual;
            usadas = 0;
        }
        if (usadas >= maximo) {
            return false;
        }
        usadas++;
        return true;
    }

    public int maximo() {
        return maximo;
    }
}
