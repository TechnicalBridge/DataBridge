package com.tbridge.debt.service;

import com.tbridge.debt.dto.response.ReglasResponse;
import com.tbridge.debt.dto.response.ReglasResponse.Regla;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** La pantalla Reglas: lo que rige en ms-debt, de que variable sale, y los mismos feriados que usan las campanas. */
class ReglasServiceTest {

    private final HorarioDeContacto horario = new HorarioDeContacto("2026-12-24");
    private final ReglasService servicio = new ReglasService(45, new BigDecimal("2.50"), 5, horario, false);
    private final ReglasResponse reglas = servicio.reglas(LocalDate.of(2026, 10, 15));

    private Regla regla(String que) {
        return reglas.grupos().stream()
                .flatMap(grupo -> grupo.reglas().stream())
                .filter(r -> r.que().startsWith(que))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no esta la regla: " + que));
    }

    @Test
    void cada_regla_dice_lo_que_vale_y_de_que_variable_sale() {
        assertEquals("ms-debt", reglas.servicio());
        assertEquals("45 días de mora, o más", regla("Una deuda entra a cobranza").valor());
        assertEquals("MIN_DIAS_MORA", regla("Una deuda entra a cobranza").variable());
        assertEquals("2,5 % mensual", regla("Tasa de interés máxima").valor());
        assertEquals("INTERES_TASA_MAXIMA_MENSUAL", regla("Tasa de interés máxima").variable());
        assertEquals("5 días antes de que venza", regla("El recordatorio de una cuota").valor());
        assertEquals("RECORDATORIO_DIAS_ANTES", regla("El recordatorio de una cuota").variable());
    }

    @Test
    void dice_que_los_deudores_entran_solo_por_campana_salvo_que_se_encienda_la_api() {
        assertEquals("Solo por campaña, cargados desde el portal", regla("Cómo entran los deudores").valor());
        assertEquals("INTEGRACION_API", regla("Cómo entran los deudores").variable());

        Regla conLaApi = new ReglasService(45, new BigDecimal("2.50"), 5, horario, true)
                .reglas(LocalDate.of(2026, 10, 15)).grupos().getFirst().reglas().getFirst();
        assertEquals("Por campaña, desde el portal, y también por la API con una clave", conLaApi.valor());
    }

    @Test
    void lo_que_fija_la_ley_lo_dice_en_vez_de_una_variable() {
        Regla cuando = regla("Cuándo se le puede escribir");
        assertEquals("De lunes a sábado, de 8:00 a 20:00, y nunca un feriado", cuando.valor());
        assertNull(cuando.variable());
        assertEquals("Ley 19.496, art. 37", cuando.nota());
        assertEquals("2 correos por semana, con al menos 2 días entre uno y otro", regla("Cuántas veces").valor());
    }

    @Test
    void si_el_horario_esta_apagado_para_probar_lo_dice_a_gritos() {
        ReglasResponse conElHorarioApagado = new ReglasService(45, new BigDecimal("2.50"), 5,
                new HorarioDeContacto("", true), false).reglas(LocalDate.of(2026, 10, 15));
        Regla cuando = conElHorarioApagado.grupos().stream()
                .flatMap(grupo -> grupo.reglas().stream())
                .filter(r -> r.que().equals("Cuándo se le puede escribir"))
                .findFirst()
                .orElseThrow();

        assertTrue(cuando.valor().startsWith("APAGADO PARA PROBAR"), cuando.valor());
        assertEquals("CONTACTO_SIN_HORARIO", cuando.variable());
    }

    @Test
    void los_feriados_son_los_mismos_que_usan_las_campanas_y_dicen_cual_se_decreto() {
        Regla de2026 = regla("Feriados de 2026");
        Regla de2027 = regla("Feriados de 2027");

        assertEquals(horario.feriadosDe(2026).size(), de2026.lista().size());
        assertEquals(de2026.lista().size() + " días sin correos", de2026.valor());
        assertTrue(de2026.lista().contains("lunes 12 de octubre"), de2026.lista().toString());
        assertTrue(de2026.lista().contains("jueves 24 de diciembre (decretado)"), de2026.lista().toString());
        assertEquals("CONTACTO_FERIADOS", de2026.variable());
        assertEquals(horario.feriadosDe(2027).size(), de2027.lista().size());
        //  Cada dia que muestra es uno en que la campana no le escribe a nadie.
        for (LocalDate dia : horario.feriadosDe(2026)) {
            assertFalse(horario.permite(dia.atTime(10, 0)), dia.toString());
        }
    }
}
