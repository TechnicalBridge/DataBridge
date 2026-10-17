package com.tbridge.debt.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cada forma de un contacto lleva lo suyo y nada mas: es lo que la tabla exige
 * con sus reglas (ck_contact_campaign, ck_contact_reminder).
 */
class ContactTest {

    private final Debt deuda = new Debt();

    @Test
    void la_invitacion_y_el_reenvio_solo_llevan_la_deuda_y_el_destino() {
        for (Contact contacto : new Contact[] {
                Contact.invitacion(deuda, "fe**********@correo.cl"), Contact.reenvio(deuda, "fe**********@correo.cl")}) {
            assertSame(deuda, contacto.getDebt());
            assertEquals("fe**********@correo.cl", contacto.getDestination());
            assertEquals(Contact.Channel.email, contacto.getChannel());
            assertNull(contacto.getCampaign());
            assertNull(contacto.getTouchNumber());
            assertNull(contacto.getInstallment());
            assertNotNull(contacto.getOccurredAt(), "si nadie dice otra cosa, es ahora");
        }
        assertEquals(Contact.Kind.invitation, Contact.invitacion(deuda, null).getKind());
        assertEquals(Contact.Kind.resend, Contact.reenvio(deuda, null).getKind());
    }

    @Test
    void solo_el_reenvio_es_de_la_empresa() {
        assertTrue(Contact.reenvio(deuda, null).deLaEmpresa());
        assertFalse(Contact.invitacion(deuda, null).deLaEmpresa());
        assertFalse(Contact.toque(deuda, new Campaign(), 1, null).deLaEmpresa());
    }

    @Test
    void el_toque_lleva_su_campana_y_su_numero() {
        Campaign campana = new Campaign();

        Contact toque = Contact.toque(deuda, campana, 3, "fe**********@correo.cl");

        assertEquals(Contact.Kind.campaign, toque.getKind());
        assertSame(campana, toque.getCampaign());
        assertEquals((short) 3, toque.getTouchNumber());
        assertNull(toque.getInstallment());
    }

    @Test
    void el_recordatorio_lleva_su_cuota_y_la_deuda_es_la_de_la_cuota() {
        Installment cuota = new Installment();
        cuota.setDebt(deuda);

        Contact recordatorio = Contact.recordatorio(cuota, "fe**********@correo.cl");

        assertEquals(Contact.Kind.reminder, recordatorio.getKind());
        assertSame(cuota, recordatorio.getInstallment());
        assertSame(deuda, recordatorio.getDebt());
        assertNull(recordatorio.getCampaign());
        assertNull(recordatorio.getTouchNumber());
    }

    @Test
    void se_puede_fechar_a_otra_hora_que_la_de_ahora() {
        Instant cuando = Instant.parse("2026-10-06T13:00:00Z");

        assertEquals(cuando, Contact.invitacion(deuda, null).el(cuando).getOccurredAt());
    }
}
