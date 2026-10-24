package com.tbridge.auth.service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * Cuantos correos desvia el modo de prueba en un dia de Chile.
 *
 * <p>Un desvio sale por el proveedor igual que un envio, asi que gasta su cupo,
 * que en Brevo es diario. Sin tope, una carga de prueba con mil deudores lo
 * agotaba en un minuto y despues no salia ni el codigo de una persona de
 * verdad (#236). Pasado el tope, lo desviado no sale y queda en el log. Con 0
 * no hay tope.
 */
final class TopeDeDesvio {

    private static final ZoneId CHILE = ZoneId.of("America/Santiago");

    private final int maximo;
    private final Clock reloj;
    private LocalDate dia;
    private int usados;

    TopeDeDesvio(int maximo, Clock reloj) {
        this.maximo = Math.max(maximo, 0);
        this.reloj = reloj;
    }

    /** Toma un desvio del dia. Falso si hoy ya se desvio el maximo. */
    synchronized boolean tomar() {
        if (maximo == 0) {
            return true;
        }
        LocalDate hoy = reloj.instant().atZone(CHILE).toLocalDate();
        if (!hoy.equals(dia)) {
            dia = hoy;
            usados = 0;
        }
        if (usados >= maximo) {
            return false;
        }
        usados++;
        return true;
    }

    int maximo() {
        return maximo;
    }
}
