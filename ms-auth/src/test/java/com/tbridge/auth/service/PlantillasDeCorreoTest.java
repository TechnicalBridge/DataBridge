package com.tbridge.auth.service;

import com.tbridge.auth.service.PlantillasDeCorreo.Carta;
import org.junit.jupiter.api.Test;
import org.thymeleaf.exceptions.TemplateInputException;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Las plantillas de resources/correos: que estan las dos versiones de cada una y que se escriben enteras. */
class PlantillasDeCorreoTest {

    private final PlantillasDeCorreo plantillas = new PlantillasDeCorreo();

    private static final Map<String, Object> DATOS = Map.of(
            "acreedor", "Patrimonio Inmuebles", "portal", "databridge.cl", "codigo", "K7M2QX",
            "vence", "martes 20 de octubre", "url", "https://databridge.cl/magic?token=abc", "dura", "15 minutos",
            "nombre", "Ana Soto", "aQue", "al panel de administración", "entrada", "databridge.cl/admin/login",
            "entradaEnTrozos", List.of("databridge.cl", "/admin", "/login"));

    @Test
    void cada_correo_tiene_su_texto_y_su_html() {
        for (String nombre : List.of("codigo", "recordatorio", "enlace", "invitacion", "prueba")) {
            Carta carta = plantillas.escribir(nombre, DATOS);

            assertTrue(carta.texto().startsWith("Hola"), nombre + ": " + carta.texto());
            assertTrue(carta.texto().contains("\nDataBridge\n"), nombre + ": firmado por DataBridge");
            assertFalse(carta.texto().contains("<"), nombre + ": el texto no lleva etiquetas");
            assertFalse(carta.texto().contains("\r"), nombre + ": siempre con LF, aunque el checkout tenga CRLF");
            assertTrue(carta.html().contains("<html lang=\"es\">"), nombre);
            assertTrue(carta.html().contains("</html>"), nombre + ": entero");
            //  Los comentarios de la plantilla se quedan en la plantilla.
            assertFalse(carta.html().contains("<!--/*"), nombre);
            assertFalse(carta.html().contains("El molde de todos los correos"), nombre);
        }
    }

    @Test
    void lo_que_ve_el_buzon_antes_de_abrirlo_no_trae_el_codigo() {
        Carta carta = plantillas.escribir("codigo", DATOS);

        String adelanto = carta.html().split("mso-hide:all;\">")[1].split("</div>")[0];
        assertTrue(adelanto.contains("Patrimonio Inmuebles te dejó un mensaje"), adelanto);
        //  El adelanto se ve en la pantalla bloqueada del telefono.
        assertFalse(adelanto.contains("K7M2QX"), adelanto);
    }

    @Test
    void una_plantilla_que_no_existe_falla_en_vez_de_mandar_un_correo_vacio() {
        assertThrows(TemplateInputException.class, () -> plantillas.escribir("no-existe", DATOS));
    }
}
