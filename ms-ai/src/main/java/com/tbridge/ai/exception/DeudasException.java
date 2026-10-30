package com.tbridge.ai.exception;

/** A local category, never an upstream body or credential. */
public final class DeudasException extends RuntimeException {
    public enum Categoria {
        NO_AUTENTICADO(401, "No autorizado"),
        NO_AUTORIZADO(403, "No tienes permiso para esto"),
        DEUDOR_NO_REGISTRADO(404, "La sesion no corresponde a un deudor registrado"),
        NO_DISPONIBLE(503, "El servicio de deudas esta temporalmente no disponible"),
        PROTOCOLO(502, "No se pudo interpretar la respuesta del servicio de deudas");
        final int status;
        final String mensaje;
        Categoria(int status, String mensaje) { this.status = status; this.mensaje = mensaje; }
    }
    private final Categoria categoria;
    public DeudasException(Categoria categoria) { super(categoria.mensaje); this.categoria = categoria; }
    public Categoria categoria() { return categoria; }
    public int status() { return categoria.status; }
}
