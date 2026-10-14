package com.tbridge.auth.exception;

/**
 * El servidor de correo no acepto un correo que alguien espera saber si salio:
 * el del codigo de acceso o el del recordatorio de una cuota.
 *
 * <p>Dice de quien es la culpa, porque lo que hace quien lo pidio depende de
 * eso. Si es <b>de la direccion</b> (el servidor la rechazo, o esta mal
 * escrita), insistir no sirve. Si es <b>del servidor</b> (credenciales, cupo
 * del dia, no responde), el mismo correo sale mas tarde sin cambiar nada del
 * deudor.
 */
public class CorreoNoSalio extends RuntimeException {

    private final boolean deLaDireccion;

    /** @param motivo lo que dijo el servidor, ya en una linea y sin la clave. */
    public CorreoNoSalio(String motivo, boolean deLaDireccion) {
        super(motivo);
        this.deLaDireccion = deLaDireccion;
    }

    public boolean deLaDireccion() {
        return deLaDireccion;
    }
}
