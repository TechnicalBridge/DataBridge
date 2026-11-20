package com.tbridge.debt.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HashTest {

    @Test
    void sha256_da_el_vector_de_referencia() {
        //  El vector "abc" de FIPS 180-2: si esto no calza, ninguna huella guardada calza.
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Hash.sha256("abc"));
        assertEquals(64, Hash.sha256("").length());
    }

    @Test
    void la_comparacion_distingue_y_no_acepta_nulos() {
        assertTrue(Hash.igualesEnTiempoConstante("abc", "abc"));
        assertFalse(Hash.igualesEnTiempoConstante("abc", "abd"));
        assertFalse(Hash.igualesEnTiempoConstante("abc", "abcd"));
        assertFalse(Hash.igualesEnTiempoConstante(null, "abc"));
        assertFalse(Hash.igualesEnTiempoConstante("abc", null));
        assertFalse(Hash.igualesEnTiempoConstante(null, null));
    }
}
