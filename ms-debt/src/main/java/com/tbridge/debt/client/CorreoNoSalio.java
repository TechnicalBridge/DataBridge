package com.tbridge.debt.client;

import com.tbridge.debt.exception.ApiException;
import org.springframework.http.HttpStatus;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ms-auth emitio el codigo, pero el servidor de correo no acepto el correo: el
 * deudor no recibio nada, y ms-auth ya anulo ese codigo.
 *
 * <p>No es un contacto: no se anota como tal ni cuenta para el limite de la
 * ley. Si llega hasta quien pidio el correo, es un {@code 502} con el motivo.
 */
public class CorreoNoSalio extends ApiException {

    private static final Pattern CORREO = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    private final String motivo;
    private final boolean deLaDireccion;

    /**
     * @param respuesta     lo que respondio el servidor de correo, segun ms-auth.
     * @param deLaDireccion si el servidor rechazo esa direccion. Si no, fue el
     *                      servidor mismo, y el correo sale mas tarde sin
     *                      cambiar nada del deudor.
     */
    public CorreoNoSalio(String respuesta, boolean deLaDireccion) {
        super(HttpStatus.BAD_GATEWAY, "No se pudo enviar el correo: " + motivo(respuesta, deLaDireccion));
        this.motivo = motivo(respuesta, deLaDireccion);
        this.deLaDireccion = deLaDireccion;
    }

    /** El motivo en palabras para la empresa, con las direcciones tapadas y en no mas de 300 letras. */
    public String motivo() {
        return motivo;
    }

    public boolean deLaDireccion() {
        return deLaDireccion;
    }

    private static String motivo(String respuesta, boolean deLaDireccion) {
        String dijo = respuesta == null || respuesta.isBlank() ? "sin detalle" : tapar(respuesta.strip());
        String texto = deLaDireccion
                ? "el servidor rechazo la direccion (" + dijo + ")"
                : "el servidor de correo de DataBridge no lo acepto (" + dijo + "). Se reintenta solo";
        return texto.length() > 300 ? texto.substring(0, 297) + "..." : texto;
    }

    /** Un servidor suele repetir la direccion en su respuesta: aqui va tapada, como en todo el portal. */
    private static String tapar(String texto) {
        Matcher correo = CORREO.matcher(texto);
        StringBuilder tapado = new StringBuilder();
        while (correo.find()) {
            String direccion = correo.group();
            int arroba = direccion.indexOf('@');
            String reemplazo = arroba <= 2 ? "***" + direccion.substring(arroba)
                    : direccion.substring(0, 2) + "*".repeat(arroba - 2) + direccion.substring(arroba);
            correo.appendReplacement(tapado, Matcher.quoteReplacement(reemplazo));
        }
        correo.appendTail(tapado);
        return tapado.toString();
    }
}
