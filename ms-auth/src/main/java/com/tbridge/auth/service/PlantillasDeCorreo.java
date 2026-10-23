package com.tbridge.auth.service;

import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Lo que dice cada correo. Vive en {@code resources/correos}, no en el codigo:
 * cada uno tiene su version en HTML y su version en texto, y salen juntas.
 *
 * <p>En el HTML, Thymeleaf escapa cada valor. El nombre de un acreedor lo
 * escribe una empresa, y no puede meter etiquetas ni enlaces en el correo de
 * un deudor.
 */
final class PlantillasDeCorreo {

    /** Un correo escrito: el texto, para quien no ve HTML, y el HTML. */
    record Carta(String texto, String html) {
    }

    private final TemplateEngine motor = new TemplateEngine();

    PlantillasDeCorreo() {
        motor.addTemplateResolver(resolutor(TemplateMode.HTML, "*.html", 1));
        motor.addTemplateResolver(resolutor(TemplateMode.TEXT, "*.txt", 2));
    }

    private static ClassLoaderTemplateResolver resolutor(TemplateMode modo, String patron, int orden) {
        ClassLoaderTemplateResolver resolutor = new ClassLoaderTemplateResolver();
        resolutor.setPrefix("correos/");
        resolutor.setTemplateMode(modo);
        resolutor.setResolvablePatterns(Set.of(patron));
        resolutor.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolutor.setCheckExistence(true);
        resolutor.setOrder(orden);
        return resolutor;
    }

    /** Escribe el correo {@code nombre} ({@code nombre.txt} y {@code nombre.html}) con estos datos. */
    Carta escribir(String nombre, Map<String, ?> datos) {
        Context contexto = new Context(Locale.forLanguageTag("es-CL"));
        datos.forEach(contexto::setVariable);
        return new Carta(unix(motor.process(nombre + ".txt", contexto)), unix(motor.process(nombre + ".html", contexto)));
    }

    /**
     * Los saltos de linea, siempre LF: en un checkout de Windows las plantillas
     * quedan con CRLF. Al salir, JavaMail los pasa a lo que pide el correo.
     */
    private static String unix(String texto) {
        return texto.replace("\r\n", "\n");
    }
}
