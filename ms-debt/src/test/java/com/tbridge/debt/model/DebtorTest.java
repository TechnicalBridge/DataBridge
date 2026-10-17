package com.tbridge.debt.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Lo que queda en el deudor cuando un correo a su direccion no sale. */
class DebtorTest {

    private static final Instant AHORA = Instant.parse("2026-10-14T13:00:00Z");

    private static Debtor conCorreo(String correo) {
        Debtor deudor = new Debtor();
        deudor.setEmail(correo);
        return deudor;
    }

    @Test
    void tres_rechazos_seguidos_de_la_direccion_la_dejan_con_problemas() {
        Debtor deudor = conCorreo("felipe.rojas@correo.cl");

        deudor.correoNoSalio("el servidor rechazo la direccion (550)", true, AHORA);
        deudor.correoNoSalio("el servidor rechazo la direccion (550)", true, AHORA);
        assertFalse(deudor.correoConProblemas(), "dos no bastan");
        deudor.correoNoSalio("el servidor rechazo la direccion (550)", true, AHORA);

        assertTrue(deudor.correoConProblemas());
        assertEquals(3, deudor.getEmailRejections());
        assertEquals(AHORA, deudor.getEmailFailedAt());
    }

    @Test
    void una_falla_del_servidor_queda_anotada_pero_no_cuenta_contra_la_direccion() {
        Debtor deudor = conCorreo("felipe.rojas@correo.cl");

        for (int i = 0; i < 5; i++) {
            deudor.correoNoSalio("el servidor de correo de DataBridge no lo acepto (535)", false, AHORA);
        }

        assertFalse(deudor.correoConProblemas(), "con las credenciales corregidas, se le vuelve a escribir");
        assertEquals(0, deudor.getEmailRejections());
        assertEquals("el servidor de correo de DataBridge no lo acepto (535)", deudor.getEmailFailure());
    }

    @Test
    void un_correo_que_sale_deja_todo_en_cero() {
        Debtor deudor = conCorreo("felipe.rojas@correo.cl");
        deudor.correoNoSalio("rechazada", true, AHORA);
        deudor.correoNoSalio("rechazada", true, AHORA);

        deudor.correoSalio();

        assertNull(deudor.getEmailFailedAt());
        assertNull(deudor.getEmailFailure());
        assertEquals(0, deudor.getEmailRejections());
    }

    @Test
    void otro_correo_parte_de_cero_y_el_mismo_no() {
        Debtor deudor = conCorreo("felipe.rojas@correo.cl");
        for (int i = 0; i < 3; i++) {
            deudor.correoNoSalio("rechazada", true, AHORA);
        }

        deudor.setEmail(" Felipe.Rojas@Correo.cl ");
        assertTrue(deudor.correoConProblemas(), "la misma direccion, escrita de otra forma, sigue con problemas");

        deudor.setEmail("felipe.rojas@otro.cl");
        assertFalse(deudor.correoConProblemas(), "una cartera con otro correo vuelve a intentar");
        assertNull(deudor.getEmailFailedAt());
    }

    @Test
    void sin_motivo_queda_dicho_que_no_hubo_detalle_y_uno_largo_se_corta() {
        Debtor deudor = conCorreo("felipe.rojas@correo.cl");

        deudor.correoNoSalio(null, false, AHORA);
        assertEquals("sin detalle", deudor.getEmailFailure(), "la base pide motivo cuando hay fecha");

        deudor.correoNoSalio("x".repeat(400), false, AHORA);
        assertEquals(300, deudor.getEmailFailure().length());
    }
}
