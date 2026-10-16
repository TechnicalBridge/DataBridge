package com.tbridge.debt.exception;

import java.util.List;

/**
 * El lote completo no se puede procesar: nada de el entra.
 *
 * <p>Distinta de un rechazo por deuda. Una deuda mal formada se rechaza sola y
 * las demas siguen; esto es para lo que invalida el envio entero, como un
 * acreedor que no existe o un numero de lote reutilizado.
 */
public class CarteraInvalida extends RuntimeException {

    private final String codigo;
    private final int status;
    private final List<String> detalles;

    public CarteraInvalida(String codigo, String mensaje) {
        this(codigo, mensaje, 400);
    }

    public CarteraInvalida(String codigo, String mensaje, int status) {
        this(codigo, mensaje, status, List.of());
    }

    /** Con cada problema por separado: los de una planilla, con su hoja, fila y columna. */
    public CarteraInvalida(String codigo, String mensaje, int status, List<String> detalles) {
        super(mensaje);
        this.codigo = codigo;
        this.status = status;
        this.detalles = List.copyOf(detalles);
    }

    public String getCodigo() {
        return codigo;
    }

    public int getStatus() {
        return status;
    }

    public List<String> getDetalles() {
        return detalles;
    }
}
