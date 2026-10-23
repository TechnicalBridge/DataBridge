package com.tbridge.auth.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * A quien se le puede escribir de verdad.
 *
 * <p>Los datos de demostracion tienen correos inventados con dominios que
 * pueden existir. Con un servidor de correo real, la invitacion y la campana
 * les escribirian a personas que no tienen nada que ver, y la cuenta que manda
 * terminaria bloqueada por rebotes y reportes de spam. El modo de prueba lo
 * evita con dos listas:
 *
 * <ul>
 *   <li><b>{@code CORREO_SOLO_A}</b>: las direcciones o dominios a los que si
 *       se escribe directo, separados por coma. Una direccion incluye sus alias
 *       con {@code +}: {@code equipo@gmail.com} deja pasar
 *       {@code equipo+deudor1@gmail.com}. Un dominio va con arroba:
 *       {@code @empresa-prueba.cl}.</li>
 *   <li><b>{@code CORREO_DESVIAR_A}</b>: a donde va todo lo demas. Llega con el
 *       destinatario original en el asunto.</li>
 * </ul>
 *
 * <p>Con lista y sin desvio, lo que no esta en la lista no sale. Sin ninguna de
 * las dos se le escribe a cualquier direccion: es el modo de produccion.
 */
public final class ModoDePrueba {

    /** A donde sale un correo, y con que asunto. */
    public record Destino(String a, String asunto, boolean desviado) {
    }

    private final List<String> permitidos;
    private final String desvio;

    public ModoDePrueba(String soloA, String desviarA) {
        this.permitidos = new ArrayList<>();
        for (String entrada : (soloA == null ? "" : soloA).split(",")) {
            String limpia = entrada.strip().toLowerCase(Locale.ROOT);
            if (!limpia.isEmpty()) {
                permitidos.add(limpia);
            }
        }
        this.desvio = desviarA == null || desviarA.isBlank() ? null : desviarA.strip().toLowerCase(Locale.ROOT);
    }

    /** Cuantas direcciones o dominios reciben directo. */
    public int cuantosPermitidos() {
        return permitidos.size();
    }

    /** Si lo que no esta permitido se desvia a un buzon, en vez de no salir. */
    public boolean desvia() {
        return desvio != null;
    }

    /** Si hay alguna restriccion. Sin ninguna, se le escribe a cualquiera. */
    public boolean activo() {
        return !permitidos.isEmpty() || desvio != null;
    }

    /**
     * A donde sale el correo que iba para {@code destinatario}. Vacio si no
     * sale: no esta permitido y no hay a donde desviarlo.
     */
    public Optional<Destino> para(String destinatario, String asunto) {
        if (!activo() || permitido(destinatario)) {
            return Optional.of(new Destino(destinatario, asunto, false));
        }
        if (desvio == null) {
            return Optional.empty();
        }
        return Optional.of(new Destino(desvio, "[para " + destinatario + "] " + asunto, true));
    }

    private boolean permitido(String destinatario) {
        String direccion = destinatario == null ? "" : destinatario.strip().toLowerCase(Locale.ROOT);
        String sinAlias = sinAlias(direccion);
        //  El buzon de desvio tambien recibe directo: desviarle un correo a si mismo no agrega nada.
        if (desvio != null && (direccion.equals(desvio) || sinAlias.equals(desvio))) {
            return true;
        }
        for (String entrada : permitidos) {
            boolean calza = entrada.startsWith("@")
                    ? direccion.endsWith(entrada)
                    : direccion.equals(entrada) || sinAlias.equals(entrada);
            if (calza) {
                return true;
            }
        }
        return false;
    }

    /** {@code equipo+deudor1@gmail.com} es el buzon {@code equipo@gmail.com}. */
    private static String sinAlias(String direccion) {
        int arroba = direccion.lastIndexOf('@');
        int mas = direccion.indexOf('+');
        return mas > 0 && mas < arroba ? direccion.substring(0, mas) + direccion.substring(arroba) : direccion;
    }

    /** Como se le dice a una persona en que modo esta, para el log de arranque y la prueba de correo. */
    public String descripcion() {
        if (!activo()) {
            return "sin modo de prueba: se le escribe a cualquier direccion";
        }
        String directo = permitidos.isEmpty() ? "a nadie mas" : "solo a " + String.join(", ", permitidos);
        return "modo de prueba: directo " + directo
                + (desvio == null ? "; lo demas no sale" : "; lo demas se desvia a " + desvio);
    }
}
