package com.tbridge.auth.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RutTest {

    @Test
    void normalizar_elimina_puntos_espacios_y_convierte_a_mayusculas() {
        assertEquals("76000000-0", Rut.normalizar("76.000.000-0"));
        assertEquals("76000000-0", Rut.normalizar(" 76000000-0 "));
        assertEquals("76000000-0", Rut.normalizar("760000000"));
        assertEquals("10000013-K", Rut.normalizar("10.000.013-k"));
        assertEquals("10000013-K", Rut.normalizar("10000013k"));
        assertEquals("", Rut.normalizar(null));
        assertEquals("", Rut.normalizar("   "));
    }

    @Test
    void tieneFormato_valida_estructura_canonica() {
        assertTrue(Rut.tieneFormato("76000000-0"));
        assertTrue(Rut.tieneFormato("10000013-K"));
        assertFalse(Rut.tieneFormato("76.000.000-0"));
        assertFalse(Rut.tieneFormato("760000000"));
        assertFalse(Rut.tieneFormato("123-4"));
        assertFalse(Rut.tieneFormato(null));
        assertFalse(Rut.tieneFormato(""));
    }

    @Test
    void digitoVerificador_calcula_modulo_11_correctamente() {
        assertEquals("0", Rut.digitoVerificador("76000000"));
        assertEquals("1", Rut.digitoVerificador("11111111"));
        assertEquals("K", Rut.digitoVerificador("10000013"));
    }

    @Test
    void esValido_valida_ruts_ficticios_y_rechaza_digito_incorrecto() {
        assertTrue(Rut.esValido("76.000.000-0"));
        assertTrue(Rut.esValido("76000000-0"));
        assertTrue(Rut.esValido("760000000"));
        assertTrue(Rut.esValido("11.111.111-1"));
        assertTrue(Rut.esValido("10.000.013-K"));
        assertTrue(Rut.esValido("10000013-k"));

        assertFalse(Rut.esValido("76.000.000-8"));
        assertFalse(Rut.esValido("11.111.111-0"));
        assertFalse(Rut.esValido("invalido"));
        assertFalse(Rut.esValido(null));
        assertFalse(Rut.esValido(""));
    }
}
