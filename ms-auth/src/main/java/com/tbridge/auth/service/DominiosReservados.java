package com.tbridge.auth.service;

import java.util.List;
import java.util.Locale;

/**
 * Los dominios reservados para documentacion y pruebas (RFC 2606 y RFC 6761):
 * {@code example.com}, {@code example.net}, {@code example.org} y lo que termina
 * en {@code .example}, {@code .test}, {@code .invalid} o {@code .localhost}.
 *
 * <p>Nunca entregan correo. Mandarles algo por un proveedor real no le llega a
 * nadie y solo gasta su cupo: una planilla de prueba con mil deudores en
 * {@code example.com} agoto en un minuto el cupo diario de Brevo (#236).
 */
public final class DominiosReservados {

    private static final List<String> DOMINIOS = List.of("example.com", "example.net", "example.org");
    private static final List<String> TERMINACIONES = List.of("example", "test", "invalid", "localhost");

    private DominiosReservados() {
    }

    /** Si la direccion es de un dominio reservado. Una direccion sin arroba no lo es. */
    public static boolean es(String direccion) {
        if (direccion == null) {
            return false;
        }
        String limpia = direccion.strip().toLowerCase(Locale.ROOT);
        int arroba = limpia.lastIndexOf('@');
        if (arroba < 0 || arroba == limpia.length() - 1) {
            return false;
        }
        String dominio = limpia.substring(arroba + 1);
        if (dominio.endsWith(".")) {
            dominio = dominio.substring(0, dominio.length() - 1);
        }
        for (String reservado : DOMINIOS) {
            if (dominio.equals(reservado) || dominio.endsWith("." + reservado)) {
                return true;
            }
        }
        for (String ultimo : TERMINACIONES) {
            if (dominio.equals(ultimo) || dominio.endsWith("." + ultimo)) {
                return true;
            }
        }
        return false;
    }
}
