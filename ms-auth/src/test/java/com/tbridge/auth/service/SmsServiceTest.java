package com.tbridge.auth.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

class SmsServiceTest {

    @Test
    void ofuscarTelefono() {
        assertEquals("****", SmsService.ofuscarTelefono(null));
        assertEquals("****", SmsService.ofuscarTelefono(""));
        assertEquals("****", SmsService.ofuscarTelefono("123"));
        assertEquals("+569****21", SmsService.ofuscarTelefono("+56987654321"));
    }

    @Test
    void enviarCodigoNoFalla() {
        SmsService servicio = new SmsService("mock");
        assertDoesNotThrow(() -> servicio.enviarCodigo("+56987654321", "ABC123", "15 minutos"));
    }
}
