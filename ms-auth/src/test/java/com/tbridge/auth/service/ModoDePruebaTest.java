package com.tbridge.auth.service;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * El modo de prueba: con un servidor de correo real, a los correos inventados
 * de la demostracion no les llega nada.
 */
class ModoDePruebaTest {

    private static final String ASUNTO = "Tu codigo de acceso";

    @Test
    void sin_ninguna_de_las_dos_variables_se_le_escribe_a_cualquiera() {
        ModoDePrueba produccion = new ModoDePrueba("", null);

        assertFalse(produccion.activo());
        assertEquals(Optional.of(new ModoDePrueba.Destino("sebastian.nunez@correo.cl", ASUNTO, false)),
                produccion.para("sebastian.nunez@correo.cl", ASUNTO));
    }

    @Test
    void un_deudor_de_la_demostracion_no_recibe_nada_y_su_correo_llega_al_buzon_de_desvio() {
        ModoDePrueba modo = new ModoDePrueba("equipo@gmail.com", "equipo@gmail.com");

        ModoDePrueba.Destino destino = modo.para("sebastian.nunez@correo.cl", ASUNTO).orElseThrow();

        assertEquals("equipo@gmail.com", destino.a());
        assertEquals("[para sebastian.nunez@correo.cl] Tu codigo de acceso", destino.asunto(),
                "el asunto dice para quien era");
        assertTrue(destino.desviado());
    }

    @Test
    void una_direccion_de_la_lista_y_sus_alias_reciben_directo() {
        ModoDePrueba modo = new ModoDePrueba("equipo@gmail.com", "otro.buzon@gmail.com");

        for (String permitida : new String[]{"equipo@gmail.com", "Equipo@Gmail.com", "equipo+deudor1@gmail.com",
                "equipo+empresa.test@gmail.com"}) {
            ModoDePrueba.Destino destino = modo.para(permitida, ASUNTO).orElseThrow();
            assertEquals(permitida, destino.a(), permitida);
            assertEquals(ASUNTO, destino.asunto(), "sin tocar el asunto");
            assertFalse(destino.desviado());
        }
    }

    @Test
    void parecerse_a_una_direccion_de_la_lista_no_alcanza() {
        ModoDePrueba modo = new ModoDePrueba("equipo@gmail.com", "desvio@gmail.com");

        for (String ajena : new String[]{"equipo@gmail.com.cl", "otro.equipo@gmail.com", "equipo@otro.com",
                "equipo+x@otro.com", "xequipo@gmail.com"}) {
            assertTrue(modo.para(ajena, ASUNTO).orElseThrow().desviado(), ajena);
        }
    }

    @Test
    void un_dominio_de_la_lista_deja_pasar_todas_sus_direcciones_y_ninguna_de_un_subdominio() {
        ModoDePrueba modo = new ModoDePrueba(" equipo@gmail.com , @Empresa-Prueba.cl ", "desvio@gmail.com");

        assertFalse(modo.para("ana.soto@empresa-prueba.cl", ASUNTO).orElseThrow().desviado());
        assertTrue(modo.para("ana.soto@otra.empresa-prueba.cl", ASUNTO).orElseThrow().desviado());
        assertTrue(modo.para("ana.soto@noempresa-prueba.cl", ASUNTO).orElseThrow().desviado());
    }

    @Test
    void con_lista_y_sin_desvio_lo_que_esta_fuera_no_sale() {
        ModoDePrueba modo = new ModoDePrueba("equipo@gmail.com", "");

        assertTrue(modo.activo());
        assertTrue(modo.para("sebastian.nunez@correo.cl", ASUNTO).isEmpty());
        assertTrue(modo.para("equipo+deudor1@gmail.com", ASUNTO).isPresent());
    }

    @Test
    void solo_con_desvio_todo_va_al_buzon_de_desvio_que_recibe_directo() {
        ModoDePrueba modo = new ModoDePrueba(null, "Desvio@gmail.com");

        assertTrue(modo.para("camila.reyes@apofyx.cl", ASUNTO).orElseThrow().desviado());
        assertEquals("desvio@gmail.com", modo.para("camila.reyes@apofyx.cl", ASUNTO).orElseThrow().a());
        //  Desviarle un correo a si mismo no agrega nada: llega tal cual.
        assertFalse(modo.para("desvio@gmail.com", ASUNTO).orElseThrow().desviado());
        assertFalse(modo.para("desvio+admin@gmail.com", ASUNTO).orElseThrow().desviado());
    }

    @Test
    void dice_en_que_modo_esta_con_palabras_de_una_persona() {
        assertEquals("sin modo de prueba: se le escribe a cualquier direccion", new ModoDePrueba("", "").descripcion());
        assertEquals("modo de prueba: directo solo a equipo@gmail.com, @empresa-prueba.cl; lo demas se desvia a desvio@gmail.com",
                new ModoDePrueba("equipo@gmail.com,@empresa-prueba.cl", "desvio@gmail.com").descripcion());
        assertEquals("modo de prueba: directo solo a equipo@gmail.com; lo demas no sale",
                new ModoDePrueba("equipo@gmail.com", null).descripcion());
    }
}
