package com.tbridge.debt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tbridge.debt.exception.CarteraInvalida;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** La planilla en Excel: una hoja de deudores, otra de deudas con una fila por mes, y cada problema con su lugar. */
class CarteraExcelTest {

    private static final CarteraCsv.Lote LOTE = new CarteraCsv.Lote("CARGA-1", "2026-10-15", "76418902-7", null,
            "CMP-1");

    private static ObjectNode leer(PlanillaDePrueba planilla) {
        return CarteraExcel.leer(planilla.bytes(), LOTE);
    }

    private static List<String> problemas(PlanillaDePrueba planilla) {
        CarteraInvalida fallo = assertThrows(CarteraInvalida.class, () -> leer(planilla));
        assertEquals("excel_invalido", fallo.getCodigo());
        return fallo.getDetalles().isEmpty() ? List.of(fallo.getMessage()) : fallo.getDetalles();
    }

    private static PlanillaDePrueba felipe() {
        return new PlanillaDePrueba()
                .deudor("16.482.337-7", "persona", "Felipe Rojas Muñoz", "felipe.rojas@correo.cl", "+56 9 4412 0387");
    }

    @Test
    void una_persona_que_debe_tres_meses_es_una_deuda_con_tres_cargos() {
        JsonNode cartera = leer(felipe()
                .deuda("CTR-2026-014", "16.482.337-7", "registrar", "CLP", "Arriendo Depto. 302",
                        YearMonth.of(2026, 7), 350000, LocalDate.of(2026, 7, 5))
                .deuda("CTR-2026-014", "16.482.337-7", "registrar", "CLP", "Arriendo Depto. 302",
                        YearMonth.of(2026, 8), 350000, LocalDate.of(2026, 8, 5), "Arriendo agosto")
                .deuda("CTR-2026-014", "16.482.337-7", "registrar", "CLP", "Arriendo Depto. 302",
                        YearMonth.of(2026, 9), 350000, LocalDate.of(2026, 9, 5)));

        assertEquals("1.0", cartera.get("version").asText());
        assertEquals("CARGA-1", cartera.at("/lote/id_externo").asText());
        assertEquals("76418902-7", cartera.at("/lote/acreedor/rut").asText());
        assertEquals("CMP-1", cartera.at("/lote/campana_id_externo").asText());
        assertEquals(1, cartera.get("deudas").size());

        JsonNode deuda = cartera.get("deudas").get(0);
        assertEquals("CTR-2026-014", deuda.get("id_externo").asText());
        assertEquals("16482337-7", deuda.at("/deudor/rut").asText(), "el RUT se lee con o sin puntos");
        assertEquals("persona", deuda.at("/deudor/tipo").asText());
        assertEquals("Felipe Rojas Muñoz", deuda.at("/deudor/nombre").asText());
        assertEquals("felipe.rojas@correo.cl", deuda.at("/deudor/correo").asText());
        assertEquals("CLP", deuda.get("moneda").asText());
        assertEquals("Arriendo Depto. 302", deuda.get("concepto").asText());

        JsonNode cargos = deuda.get("cargos");
        assertEquals(3, cargos.size());
        assertEquals("2026-07", cargos.get(0).get("periodo").asText());
        assertEquals("Arriendo Depto. 302 · julio de 2026", cargos.get(0).get("concepto").asText(),
                "sin concepto del mes, el de la deuda y el mes");
        assertEquals("Arriendo agosto", cargos.get(1).get("concepto").asText());
        assertTrue(cargos.get(0).get("monto").isIntegralNumber(), cargos.get(0).toString());
        assertEquals(350000, cargos.get(0).get("monto").asLong());
        assertEquals("2026-09-05", cargos.get(2).get("fecha_vencimiento").asText());
    }

    @Test
    void un_monto_en_uf_con_decimales_y_uno_en_clp_entero_entran_exactos() {
        JsonNode cartera = leer(felipe()
                .deudor("76991245-2", "empresa", "Comercial Ñandú SpA", "pagos@nandu.cl", null)
                .deuda("LOC-12", "76991245-2", "registrar", "UF", "Arriendo Local 12", YearMonth.of(2026, 7), 38.45,
                        LocalDate.of(2026, 7, 5), null, 1.5)
                .deuda("CTR-1", "16482337-7", "registrar", "CLP", "Arriendo", YearMonth.of(2026, 8), 185000,
                        LocalDate.of(2026, 8, 5)));

        JsonNode uf = cartera.get("deudas").get(0);
        assertEquals(new BigDecimal("38.45"), uf.at("/cargos/0/monto").decimalValue());
        assertEquals(new BigDecimal("1.5"), uf.get("tasa_interes_mensual").decimalValue());
        assertFalse(uf.at("/deudor").has("telefono"), "sin telefono, no va");
        assertEquals("empresa", uf.at("/deudor/tipo").asText());
        assertEquals(185000, cartera.at("/deudas/1/cargos/0/monto").asLong());
    }

    @Test
    void un_cliente_al_dia_va_sin_cargos_y_un_retiro_con_su_motivo() {
        JsonNode cartera = leer(felipe()
                .deuda("CTR-2026-022", "16482337-7", "al día", "UF", "Arriendo Local 12", null, null, null)
                .deuda("CTR-2025-031", null, "retirar", null, null, null, null, null, null, null, "pago_directo"));

        JsonNode alDia = cartera.get("deudas").get(0);
        assertEquals(0, alDia.get("cargos").size());
        assertEquals("16482337-7", alDia.at("/deudor/rut").asText());
        JsonNode retiro = cartera.get("deudas").get(1);
        assertEquals("retirar", retiro.get("accion").asText());
        assertEquals("pago_directo", retiro.get("motivo_retiro").asText());
        assertFalse(retiro.has("deudor"));
    }

    @Test
    void los_meses_las_fechas_y_los_montos_escritos_como_texto_tambien_se_leen() {
        JsonNode cartera = leer(felipe()
                .deuda("CTR-1", "16482337-7", "registrar", "CLP", "Arriendo", "jul-2026", "350.000", "05-07-2026")
                .deuda("CTR-1", "16482337-7", "", "CLP", "Arriendo", "2026-08", "350000", "05/08/2026")
                .deuda("CTR-1", "16482337-7", "registrar", "CLP", "Arriendo", "septiembre de 2026", 350000,
                        "2026-09-05"));

        JsonNode cargos = cartera.at("/deudas/0/cargos");
        assertEquals(List.of("2026-07", "2026-08", "2026-09"),
                List.of(cargos.get(0).get("periodo").asText(), cargos.get(1).get("periodo").asText(),
                        cargos.get(2).get("periodo").asText()));
        assertEquals(350000, cargos.get(0).get("monto").asLong(), "350.000 son puntos de miles");
        assertEquals("2026-08-05", cargos.get(1).get("fecha_vencimiento").asText());
    }

    @Test
    void cada_problema_dice_su_hoja_su_fila_y_su_columna_y_se_juntan_todos() {
        List<String> problemas = problemas(felipe()
                .deuda("CTR-1", "11111111-1", "registrar", "CLP", "Arriendo", YearMonth.of(2026, 7), 350000,
                        LocalDate.of(2026, 7, 5))
                .deuda("CTR-2", "16482337-7", "registrar", "CLP", "Arriendo", "julito", "185.000,5", "32-13-2026")
                .deuda("CTR-3", "16482337-7", "pagar", "CLP", "Arriendo", YearMonth.of(2026, 7), 1,
                        LocalDate.of(2026, 7, 5)));

        assertEquals(List.of(
                "Hoja Deudas, fila 2, columna RUT del deudor: el RUT 11111111-1 no está en la hoja Deudores",
                "Hoja Deudas, fila 3, columna Mes: 'julito' no es un mes. Escríbelo como fecha (jul-2026) o como 2026-07",
                "Hoja Deudas, fila 3, columna Monto: '185.000,5' no es un número: va sin puntos de miles, como 185000 o 38,5",
                "Hoja Deudas, fila 3, columna Vencimiento: '32-13-2026' no es una fecha. Escríbela como fecha: 05-07-2026",
                "Hoja Deudas, fila 4, columna Acción: 'pagar' no es una acción: registrar, al día o retirar"),
                problemas);
    }

    @Test
    void una_deuda_dice_lo_mismo_en_todos_sus_meses_y_cada_mes_va_una_vez() {
        List<String> problemas = problemas(felipe()
                .deuda("CTR-1", "16482337-7", "registrar", "CLP", "Arriendo", YearMonth.of(2026, 7), 350000,
                        LocalDate.of(2026, 7, 5))
                .deuda("CTR-1", "16482337-7", "registrar", "UF", "Arriendo", YearMonth.of(2026, 8), 12,
                        LocalDate.of(2026, 8, 5))
                .deuda("CTR-1", "16482337-7", "registrar", "CLP", "Arriendo", YearMonth.of(2026, 7), 350000,
                        LocalDate.of(2026, 7, 5)));

        assertEquals(List.of(
                "Hoja Deudas, fila 3, columna Moneda: no dice lo mismo que en la fila 2: la deuda CTR-1 lleva "
                        + "igual Moneda en todos sus meses",
                "Hoja Deudas, fila 4, columna Mes: el mes 2026-07 de la deuda CTR-1 ya está en la fila 2"),
                problemas);
    }

    @Test
    void los_montos_tienen_que_ser_positivos_y_en_pesos_sin_decimales() {
        List<String> problemas = problemas(felipe()
                .deuda("CTR-1", "16482337-7", "registrar", "CLP", "Arriendo", YearMonth.of(2026, 7), 350000.5,
                        LocalDate.of(2026, 7, 5))
                .deuda("CTR-2", "16482337-7", "registrar", "UF", "Local", YearMonth.of(2026, 7), 0,
                        LocalDate.of(2026, 7, 5))
                .deuda("CTR-3", "16482337-7", "registrar", "UF", "Local", YearMonth.of(2026, 7), 12.345,
                        LocalDate.of(2026, 7, 5)));

        assertEquals(List.of(
                "Hoja Deudas, fila 2, columna Monto: un monto en CLP va sin decimales: 350000.5",
                "Hoja Deudas, fila 3, columna Monto: el monto tiene que ser mayor que cero",
                "Hoja Deudas, fila 4, columna Monto: un monto en UF lleva hasta dos decimales: 12.345"),
                problemas);
    }

    @Test
    void al_registrar_faltan_el_mes_el_monto_y_el_vencimiento_y_al_dia_no_los_lleva() {
        List<String> problemas = problemas(felipe()
                .deuda("CTR-1", "16482337-7", "registrar", "CLP", "Arriendo", null, null, null)
                .deuda("CTR-2", "16482337-7", "al día", "CLP", "Arriendo", YearMonth.of(2026, 7), 1000, null));

        assertEquals(List.of(
                "Hoja Deudas, fila 2, columna Mes: falta el mes: una fila por cada mes que se debe",
                "Hoja Deudas, fila 2, columna Monto: falta el monto",
                "Hoja Deudas, fila 2, columna Vencimiento: falta el vencimiento",
                "Hoja Deudas, fila 3, columna Mes: una deuda al día no lleva Mes: el cliente no debe nada",
                "Hoja Deudas, fila 3, columna Monto: una deuda al día no lleva Monto: el cliente no debe nada"),
                problemas);
    }

    @Test
    void en_deudores_cada_persona_va_una_vez_con_su_tipo_y_su_correo_o_telefono() {
        List<String> problemas = problemas(felipe()
                .deudor("16482337-7", "persona", "Otro Felipe", "otro@correo.cl", null)
                .deudor("15227640-0", "cliente", "Tomás Fuentes", null, null)
                .deuda("CTR-1", "16482337-7", "registrar", "CLP", "Arriendo", YearMonth.of(2026, 7), 1000,
                        LocalDate.of(2026, 7, 5)));

        assertEquals(List.of(
                "Hoja Deudores, fila 3, columna RUT: el RUT 16482337-7 ya está en la fila 2. Cada persona va una "
                        + "sola vez; sus deudas, en la hoja Deudas",
                "Hoja Deudores, fila 4, columna Tipo: 'cliente' no es un tipo: persona o empresa",
                "Hoja Deudores, fila 4, columna Correo: falta el correo o el teléfono: sin ellos no le llega su código"),
                problemas);
    }

    @Test
    void un_solo_problema_va_en_el_mensaje_y_una_planilla_vacia_lo_dice() {
        CarteraInvalida fallo = assertThrows(CarteraInvalida.class, () -> leer(felipe()));
        assertEquals("La planilla tiene un problema: La hoja Deudas está vacía: no hay nada que cargar",
                fallo.getMessage());
        assertTrue(fallo.getDetalles().isEmpty());
    }

    @Test
    void lo_que_no_es_la_plantilla_se_rechaza_con_que_hacer() {
        CarteraInvalida noEsExcel = assertThrows(CarteraInvalida.class,
                () -> CarteraExcel.leer(new byte[]{'P', 'K', 3, 4, 0, 0, 0}, LOTE));
        assertTrue(noEsExcel.getMessage().startsWith("No se pudo leer el archivo como una planilla de Excel"),
                noEsExcel.getMessage());

        assertTrue(CarteraExcel.esExcel(new PlanillaDePrueba().bytes()));
        assertFalse(CarteraExcel.esExcel("deuda_id;accion".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void los_retiros_y_las_deudas_al_dia_van_en_una_sola_fila() {
        List<String> problemas = problemas(felipe()
                .deuda("RET-1", null, "retirar", null, null, null, null, null, null, null, "pago_directo")
                .deuda("RET-1", null, "retirar", null, null, null, null, null, null, null, "pago_directo")
                .deuda("RET-2", null, "retirar", null, null, null, null, null, null, null, "se fue")
                .deuda("AL-1", "16482337-7", "al dia", "CLP", "Arriendo", null, null, null)
                .deuda("AL-1", "16482337-7", "registrar", "CLP", "Arriendo", YearMonth.of(2026, 7), 1000,
                        LocalDate.of(2026, 7, 5)));

        assertEquals(List.of(
                "Hoja Deudas, fila 3, columna Id de la deuda: la deuda RET-1 ya está en la fila 2. Un retiro va "
                        + "en una sola fila",
                "Hoja Deudas, fila 4, columna Motivo del retiro: 'se fue' no es un motivo de retiro",
                "Hoja Deudas, fila 6, columna Id de la deuda: la deuda AL-1 ya está en la fila 5. Una deuda al día "
                        + "va en una sola fila, sin meses"),
                problemas);
    }
}
