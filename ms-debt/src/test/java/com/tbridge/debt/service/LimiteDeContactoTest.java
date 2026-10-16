package com.tbridge.debt.service;

import com.tbridge.debt.model.Debtor;
import com.tbridge.debt.repository.ContactRepository;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Dos gestiones por semana, con dos dias entre una y otra (Ley 19.496, art. 37). */
class LimiteDeContactoTest {

    private static final ZoneId CHILE = ZoneId.of("America/Santiago");
    private static final LocalDateTime AHORA = LocalDateTime.of(2026, 10, 14, 10, 0);

    private final ContactRepository contactos = mock(ContactRepository.class);
    private final LimiteDeContacto limite = new LimiteDeContacto(contactos);
    private final Debtor deudor = new Debtor();
    private final List<Instant> enviados = new ArrayList<>();

    private void escritoHace(int dias) {
        enviados.add(AHORA.minusDays(dias).atZone(CHILE).toInstant());
    }

    private boolean permite() {
        when(contactos.cuandoSeLeEscribio(eq(deudor), any())).thenReturn(enviados);
        return limite.permite(deudor, AHORA.atZone(CHILE).toInstant());
    }

    @Test
    void apagado_para_probar_deja_escribirle_aunque_ya_van_dos_y_fue_ayer() {
        escritoHace(3);
        escritoHace(1);
        when(contactos.cuandoSeLeEscribio(eq(deudor), any())).thenReturn(enviados);

        assertTrue(new LimiteDeContacto(contactos, true).permite(deudor, AHORA.atZone(CHILE).toInstant()));
        //  Encendido, que es lo por omision, no lo deja.
        assertFalse(permite());
    }

    @Test
    void sin_nada_esta_semana_se_le_puede_escribir() {
        assertTrue(permite());
    }

    @Test
    void ayer_ya_se_le_escribio_y_hoy_no() {
        escritoHace(1);
        assertFalse(permite());
    }

    @Test
    void con_dos_dias_de_por_medio_si() {
        escritoHace(2);
        assertTrue(permite());
    }

    @Test
    void dos_en_la_semana_es_el_tope() {
        escritoHace(5);
        escritoHace(3);
        assertFalse(permite(), "aunque la ultima fue hace tres dias");
    }

    @Test
    void lo_de_la_semana_pasada_no_cuenta() {
        //  La consulta trae desde hace seis dias; lo anterior no llega.
        when(contactos.cuandoSeLeEscribio(deudor, AHORA.toLocalDate().minusDays(6).atStartOfDay(CHILE).toInstant()))
                .thenReturn(List.of());
        assertTrue(limite.permite(deudor, AHORA.atZone(CHILE).toInstant()));
    }

    @Test
    void cuenta_todo_lo_que_se_le_escribio_sea_del_tipo_que_sea() {
        //  La consulta no mira el tipo: la invitacion, un toque, un recordatorio
        //  y un reenvio son gestiones por igual, de cualquiera de sus deudas.
        escritoHace(6);
        escritoHace(4);
        assertFalse(permite());
    }
}
