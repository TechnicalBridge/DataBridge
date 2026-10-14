package com.tbridge.auth.util;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Un correo que sirve para mandarle a una persona su enlace de acceso.
 *
 * <p>Acepta cualquier direccion valida: de cualquier proveedor, con {@code +},
 * con subdominios y con dominios nuevos ({@code .app}, {@code .cl}). Rechaza lo
 * que no puede recibir correo: sin dominio completo ({@code juan@gmail}), con
 * espacios, o sin nada antes de la arroba.
 *
 * <p>Es la misma regla de ms-debt, y cada servicio tiene su copia, igual que
 * con {@link Rut}: ninguno depende de una libreria compartida.
 */
public final class Correo {

    /** Lo que puede ir antes de la arroba (RFC 5322, sin comillas), con puntos al medio y no seguidos. */
    private static final Pattern LOCAL =
            Pattern.compile("[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+(\\.[A-Za-z0-9!#$%&'*+/=?^_`{|}~-]+)*");
    /** Etiquetas de letras, numeros y guiones, y una terminacion de letras (o xn-- en un dominio con tildes). */
    private static final Pattern DOMINIO =
            Pattern.compile("([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+([a-z]{2,63}|xn--[a-z0-9-]{1,59})");

    private Correo() {
    }

    /**
     * El correo listo para guardar: sin espacios alrededor y todo en
     * minusculas, porque el personal se busca por su correo sin distinguirlas.
     * Vacio si no es una direccion valida.
     */
    public static Optional<String> normalizar(String crudo) {
        if (crudo == null) {
            return Optional.empty();
        }
        String correo = crudo.strip();
        int arroba = correo.lastIndexOf('@');
        if (correo.length() > 254 || arroba <= 0 || arroba != correo.indexOf('@')) {
            return Optional.empty();
        }
        String local = correo.substring(0, arroba);
        String dominio = correo.substring(arroba + 1).toLowerCase(Locale.ROOT);
        if (local.length() > 64 || dominio.length() > 253
                || !LOCAL.matcher(local).matches() || !DOMINIO.matcher(dominio).matches()) {
            return Optional.empty();
        }
        return Optional.of(local.toLowerCase(Locale.ROOT) + "@" + dominio);
    }
}
