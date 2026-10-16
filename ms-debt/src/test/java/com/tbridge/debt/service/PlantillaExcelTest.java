package com.tbridge.debt.service;

import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint.ValidationType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** La plantilla: tres hojas, los encabezados que se leen, las listas y los tipos de cada columna. */
class PlantillaExcelTest {

    private static XSSFWorkbook plantilla(int minDiasMora) throws IOException {
        return new XSSFWorkbook(new ByteArrayInputStream(PlantillaExcel.generar(minDiasMora)));
    }

    private static List<String> encabezados(Sheet hoja) {
        List<String> titulos = new ArrayList<>();
        hoja.getRow(0).forEach(celda -> titulos.add(celda.getStringCellValue()));
        return titulos;
    }

    /** Lo que valida cada columna de la fila 2, por su encabezado. */
    private static Map<String, DataValidation> validaciones(Sheet hoja) {
        List<String> titulos = encabezados(hoja);
        return hoja.getDataValidations().stream().collect(Collectors.toMap(
                v -> titulos.get(v.getRegions().getCellRangeAddress(0).getFirstColumn()), v -> v));
    }

    @Test
    void trae_las_tres_hojas_con_los_encabezados_que_se_leen() throws IOException {
        try (XSSFWorkbook libro = plantilla(30)) {
            assertEquals(List.of("Instrucciones", "Deudores", "Deudas"),
                    List.of(libro.getSheetName(0), libro.getSheetName(1), libro.getSheetName(2)));
            assertEquals(CarteraExcel.COLUMNAS_DEUDORES.stream().map(CarteraExcel.Columna::titulo).toList(),
                    encabezados(libro.getSheet("Deudores")));
            assertEquals(CarteraExcel.COLUMNAS_DEUDAS.stream().map(CarteraExcel.Columna::titulo).toList(),
                    encabezados(libro.getSheet("Deudas")));
            //  Vacia: los ejemplos van en Instrucciones, para no cargarlos por error.
            assertEquals(0, libro.getSheet("Deudores").getLastRowNum());
            assertEquals(0, libro.getSheet("Deudas").getLastRowNum());
            //  Cada encabezado explica su columna.
            assertNotNull(libro.getSheet("Deudas").getRow(0).getCell(5).getCellComment());
        }
    }

    @Test
    void las_listas_son_desplegables_y_las_fechas_y_los_montos_tienen_su_tipo() throws IOException {
        try (XSSFWorkbook libro = plantilla(30)) {
            Map<String, DataValidation> deudas = validaciones(libro.getSheet("Deudas"));
            assertArrayEquals(new String[]{"registrar", "al día", "retirar"},
                    deudas.get("Acción").getValidationConstraint().getExplicitListValues());
            assertArrayEquals(new String[]{"CLP", "UF"},
                    deudas.get("Moneda").getValidationConstraint().getExplicitListValues());
            assertEquals(6, deudas.get("Motivo del retiro").getValidationConstraint().getExplicitListValues().length);
            assertEquals(ValidationType.DATE, deudas.get("Mes").getValidationConstraint().getValidationType());
            assertEquals(ValidationType.DATE, deudas.get("Vencimiento").getValidationConstraint().getValidationType());
            assertEquals(ValidationType.DECIMAL, deudas.get("Monto").getValidationConstraint().getValidationType());
            assertEquals(ValidationType.DECIMAL,
                    deudas.get("Tasa de interés mensual").getValidationConstraint().getValidationType());
            //  El RUT de una deuda tiene que estar en Deudores: un aviso, no un bloqueo.
            DataValidation rut = deudas.get("RUT del deudor");
            assertTrue(rut.getValidationConstraint().getFormula1().startsWith("COUNTIF(Deudores!$A$2:$A$"),
                    rut.getValidationConstraint().getFormula1());
            assertEquals(DataValidation.ErrorStyle.WARNING, rut.getErrorStyle());
            assertEquals(PlantillaExcel.FILAS, rut.getRegions().getCellRangeAddress(0).getLastRow());

            Map<String, DataValidation> deudores = validaciones(libro.getSheet("Deudores"));
            assertArrayEquals(new String[]{"persona", "empresa"},
                    deudores.get("Tipo").getValidationConstraint().getExplicitListValues());

            Sheet hoja = libro.getSheet("Deudas");
            assertEquals("mmm-yyyy", hoja.getColumnStyle(5).getDataFormatString());
            assertEquals("dd-mm-yyyy", hoja.getColumnStyle(7).getDataFormatString());
            assertEquals("@", hoja.getColumnStyle(0).getDataFormatString(), "el id y el RUT, como texto");
        }
    }

    @Test
    void las_instrucciones_explican_cada_columna_y_la_mora_minima_vigente() throws IOException {
        try (XSSFWorkbook libro = plantilla(45)) {
            StringBuilder texto = new StringBuilder();
            for (Row fila : libro.getSheet("Instrucciones")) {
                fila.forEach(celda -> texto.append(celda.getStringCellValue()).append(" | "));
            }
            String todo = texto.toString();
            assertTrue(todo.contains("al menos 45 días vencido"), "la mora minima sale de la configuracion");
            for (CarteraExcel.Columna c : CarteraExcel.COLUMNAS_DEUDAS) {
                assertTrue(todo.contains(c.titulo() + " | " + c.tipo()), "falta explicar " + c.titulo());
            }
            assertTrue(todo.contains("una fila por cada mes"), todo);
        }
    }

    @Test
    void la_plantilla_recien_descargada_se_lee_y_dice_que_esta_vacia() {
        CarteraExcel.leer(new PlanillaDePrueba().deudor("16482337-7", "persona", "Felipe", "f@correo.cl", null)
                .deuda("CTR-1", "16482337-7", "registrar", "CLP", "Arriendo", java.time.YearMonth.of(2026, 7),
                        1000, java.time.LocalDate.of(2026, 7, 5)).bytes(),
                new CarteraCsv.Lote("L", "2026-10-15", "76418902-7", null, "C"));
        var fallo = org.junit.jupiter.api.Assertions.assertThrows(com.tbridge.debt.exception.CarteraInvalida.class,
                () -> CarteraExcel.leer(PlantillaExcel.generar(30),
                        new CarteraCsv.Lote("L", "2026-10-15", "76418902-7", null, "C")));
        assertTrue(fallo.getMessage().contains("La hoja Deudas está vacía"), fallo.getMessage());
    }
}
