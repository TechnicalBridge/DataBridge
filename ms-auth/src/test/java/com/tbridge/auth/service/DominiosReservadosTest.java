package com.tbridge.auth.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Los dominios que nunca entregan correo (RFC 2606 y 6761), y los que si. */
class DominiosReservadosTest {

    @ParameterizedTest
    @ValueSource(strings = {"camila.reyes@example.com", "tb.prueba.1@EXAMPLE.COM", "a@example.net", "a@example.org",
            "a@correo.example.com", "a@empresa.example", "a@demo.test", "a@nada.invalid", "a@pc.localhost",
            "  a@example.com.  "})
    void estos_son_reservados(String direccion) {
        assertTrue(DominiosReservados.es(direccion), direccion);
    }

    @ParameterizedTest
    @ValueSource(strings = {"felipe.rojas@correo.cl", "equipo@gmail.com", "a@examples.com", "a@example.com.cl",
            "a@miexample.com", "a@testing.cl", "example.com", "a@", ""})
    void estos_no(String direccion) {
        assertFalse(DominiosReservados.es(direccion), direccion);
    }

    @Test
    void sin_direccion_no_es_reservado() {
        assertFalse(DominiosReservados.es(null));
    }
}
