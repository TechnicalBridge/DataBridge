package com.tbridge.ai.service;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** El motor local: responde sin LLM, con reglas. */
class MotorLocalTest {

    //  Tal como las entrega ms-debt (GET /api/debts).
    static final List<Map<String, Object>> DEUDAS = List.of(
            Map.of("acreedor", "Patrimonio Inmuebles", "concepto", "Arriendo mensual", "moneda", "CLP",
                    "montoOriginal", 1040000, "saldo", 1040000, "estado", "open"),
            Map.of("acreedor", "Patrimonio Inmuebles", "concepto", "Arriendo oficina", "moneda", "UF",
                    "montoOriginal", 115.5, "saldo", 96.25, "estado", "repacted"),
            Map.of("acreedor", "Patrimonio Inmuebles", "concepto", "Arriendo mensual", "moneda", "CLP",
                    "montoOriginal", 410000, "saldo", 0, "estado", "paid"));

    //  Una deuda con tasa, mora de hoy y descuento por pronto pago, como la entrega ms-debt.
    static final Map<String, Object> CON_TASA = Map.ofEntries(
            Map.entry("acreedor", "Patrimonio Inmuebles"), Map.entry("concepto", "Arriendo mensual"),
            Map.entry("moneda", "CLP"), Map.entry("montoOriginal", 900000), Map.entry("saldo", 900000.0),
            Map.entry("estado", "open"), Map.entry("tasaInteresMensual", 1.5), Map.entry("interesMora", 15900),
            Map.entry("totalHoy", 915900.0), Map.entry("descuentoDisponible", 7950),
            Map.entry("descuentoHasta", "2026-11-03"));
    static final Map<String, Object> SIN_TASA = Map.of(
            "acreedor", "Instituto Andes", "concepto", "Arancel", "moneda", "CLP",
            "saldo", 350000, "totalHoy", 350000, "interesMora", 0, "estado", "open");

    @Nested
    class LosMontos {

        @Test
        void pesos_y_uf_se_escriben_como_en_chile() {
            assertEquals("$1.040.000", MotorLocal.dinero(1040000));
            assertEquals("UF 96,25", MotorLocal.dinero(96.25, "UF"));
            assertEquals("UF 1.234,50", MotorLocal.dinero(1234.5, "UF"));
        }

        @Test
        void pesos_y_uf_no_se_suman() {
            assertEquals("$1.040.000 y UF 96,25", MotorLocal.totales(DEUDAS));
        }

        /** Leia remainingAmount, que ms-debt ya no manda: todo sumaba $0. */
        @Test
        void antes_decia_que_se_debian_cero_pesos() {
            String respuesta = MotorLocal.responder("cuánto debo?", DEUDAS);
            assertTrue(respuesta.contains("$1.040.000"), respuesta);
            assertTrue(respuesta.contains("UF 96,25"), respuesta);
            assertTrue(respuesta.contains("2 deuda(s) vigente(s)"), respuesta);
            assertTrue(respuesta.contains("1 ya está(n) pagada(s)"), respuesta);
        }
    }

    @Nested
    class ElSentimiento {

        @Test
        void frustracion() {
            assertEquals("frustracion", MotorLocal.sentimiento("estoy harto, esto es un abuso").etiqueta());
        }

        @Test
        void desconfianza_pesa_mas_que_la_cortesia() {
            assertEquals("desconfianza", MotorLocal.sentimiento("gracias, pero esto es una estafa?").etiqueta());
        }

        @Test
        void sin_tildes_ni_mayusculas() {
            assertEquals("frustracion", MotorLocal.sentimiento("NO TENGO PLATA").etiqueta());
        }

        @Test
        void neutral() {
            assertEquals("neutral", MotorLocal.sentimiento("cuánto debo").etiqueta());
        }
    }

    @Nested
    class ElTono {

        @Test
        void quien_desconfia_recibe_como_verificar() {
            String respuesta = MotorLocal.responder("¿esto es una estafa? cómo tienen mis datos", DEUDAS);
            assertTrue(respuesta.contains("nunca te manda un enlace"), respuesta);
            assertTrue(respuesta.contains("Patrimonio Inmuebles"), respuesta);
            assertFalse(respuesta.contains("$"), respuesta);
        }

        @Test
        void quien_no_puede_pagar_recibe_las_cuotas() {
            String respuesta = MotorLocal.responder("no tengo plata para pagar", DEUDAS);
            assertTrue(respuesta.startsWith("Entiendo que es una situación difícil."), respuesta);
            assertTrue(respuesta.contains("sin interés"), respuesta);
        }

        @Test
        void el_certificado_solo_para_lo_pagado() {
            assertTrue(MotorLocal.responder("quiero el certificado", DEUDAS).contains("1 deuda(s) pagada(s)"));
            assertTrue(MotorLocal.responder("certificado", DEUDAS.subList(0, 1)).contains("se emite cuando"));
        }
    }

    /** Lo mismo que dice el portal: la tasa que pacto el acreedor, la mora de hoy y el descuento. */
    @Nested
    class LosIntereses {

        @Test
        void lo_que_debe_es_el_total_de_hoy_con_la_mora_y_el_descuento() {
            String respuesta = MotorLocal.responder("¿cuánto debo?", List.of(CON_TASA));

            assertTrue(respuesta.contains("por $915.900 en total"), respuesta);
            assertTrue(respuesta.contains("- Patrimonio Inmuebles (Arriendo mensual): $915.900, pendiente. Incluye "
                    + "$15.900 de intereses por mora (1,5% mensual). Si pagas todo antes del 3 de noviembre, te "
                    + "descontamos $7.950 de intereses."), respuesta);
        }

        @Test
        void las_cuotas_llevan_el_interes_que_pacto() {
            String respuesta = MotorLocal.responder("puedo pagar en cuotas?", List.of(CON_TASA));

            assertTrue(respuesta.contains("las cuotas llevan el interés que pactaste (1,5% mensual)"), respuesta);
            assertFalse(respuesta.contains("No hay interés"), respuesta);
        }

        @Test
        void sin_tasa_las_cuotas_siguen_sin_interes() {
            assertTrue(MotorLocal.responder("puedo pagar en cuotas?", List.of(SIN_TASA)).contains("No hay interés"));
        }

        @Test
        void con_deudas_con_y_sin_tasa_dice_cual_lleva_interes() {
            String respuesta = MotorLocal.responder("quiero repactar", List.of(CON_TASA, SIN_TASA));

            assertTrue(respuesta.contains("Las deudas con interés pactado (Patrimonio Inmuebles (Arriendo mensual), "
                    + "1,5% mensual) lo llevan también en las cuotas; en las demás no hay interés"), respuesta);
        }

        @Test
        void a_quien_no_puede_pagar_no_le_dice_sin_interes_si_hay_tasa() {
            String respuesta = MotorLocal.responder("no tengo plata", List.of(CON_TASA));

            assertTrue(respuesta.startsWith("Entiendo que es una situación difícil. Puedes dividir lo que debes en "
                    + "3 a 24 cuotas. Tu deuda lleva el interés que pactaste (1,5% mensual), también en las cuotas"),
                    respuesta);
            assertFalse(respuesta.contains("sin interés"), respuesta);
            assertTrue(respuesta.endsWith("Tu saldo vigente es $915.900."), respuesta);
        }

        @Test
        void responde_por_los_intereses_y_el_descuento() {
            String respuesta = MotorLocal.responder("¿me cobran intereses?", List.of(CON_TASA, SIN_TASA));

            assertTrue(respuesta.contains("- Patrimonio Inmuebles (Arriendo mensual): 1,5% mensual, con $15.900 de "
                    + "intereses por mora hoy."), respuesta);
            assertTrue(respuesta.contains("Las demás no tienen interés."), respuesta);
            assertTrue(respuesta.contains("nunca el capital"), respuesta);
            assertTrue(respuesta.endsWith("de una vez:\n- Patrimonio Inmuebles (Arriendo mensual): si pagas todo "
                    + "antes del 3 de noviembre, te descontamos $7.950 de intereses."), respuesta);
        }

        @Test
        void sin_tasa_no_hay_intereses_ni_descuento() {
            assertEquals("Tu deuda no tiene interés pactado: no se cobra nada extra, ni por atraso ni en cuotas.",
                    MotorLocal.responder("hay descuento?", List.of(SIN_TASA)));
        }

        @Test
        void al_pagar_le_ofrece_el_descuento_y_sin_fecha_es_ahora() {
            Map<String, Object> sinFin = new HashMap<>(CON_TASA);
            sinFin.remove("descuentoHasta");

            assertTrue(MotorLocal.responder("quiero pagar", List.of(sinFin))
                    .endsWith("la empresa queda avisada. Si pagas todo ahora, te descontamos $7.950 de intereses."));
        }
    }
}
