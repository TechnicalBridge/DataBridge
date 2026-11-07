package com.tbridge.auth.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RutTest {

    @Test
    void normalizar_elimina_puntos_espacios_y_convierte_a_mayusculas() {
        assertEquals("76418902-7", Rut.normalizar("76.418.902-7"));
        assertEquals("76418902-7", Rut.normalizar(" 76418902-7 "));
        assertEquals("76418902-7", Rut.normalizar("764189027"));
        assertEquals("10000013-K", Rut.normalizar("10.000.013-k"));
        assertEquals("10000013-K", Rut.normalizar("10000013k"));
        assertEquals("", Rut.normalizar(null));
        assertEquals("", Rut.normalizar("   "));
    }

    @Test
    void tieneFormato_valida_estructura_canonica() {
        assertTrue(Rut.tieneFormato("76418902-7"));
        assertTrue(Rut.tieneFormato("10000013-K"));
        assertFalse(Rut.tieneFormato("76.418.902-7"));
        assertFalse(Rut.tieneFormato("764189027"));
        assertFalse(Rut.tieneFormato("123-4"));
        assertFalse(Rut.tieneFormato(null));
        assertFalse(Rut.tieneFormato(""));
    }

    @Test
    void digitoVerificador_calcula_modulo_11_correctamente() {
        assertEquals("7", Rut.digitoVerificador("76418902"));
        assertEquals("6", Rut.digitoVerificador("18905214"));
        assertEquals("K", Rut.digitoVerificador("10000013"));
    }

    @Test
    void esValido_valida_ruts_reales_y_rechaza_digito_incorrecto() {
        assertTrue(Rut.esValido("76.418.902-7"));
        assertTrue(Rut.esValido("76418902-7"));
        assertTrue(Rut.esValido("764189027"));
        assertTrue(Rut.esValido("18.905.214-6"));
        assertTrue(Rut.esValido("10.000.013-K"));
        assertTrue(Rut.esValido("10000013-k"));

        assertFalse(Rut.esValido("76.418.902-8"));
        assertFalse(Rut.esValido("18.905.214-0"));
        assertFalse(Rut.esValido("invalido"));
        assertFalse(Rut.esValido(null));
        assertFalse(Rut.esValido(""));
    }
}
