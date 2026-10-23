package com.tbridge.debt.service;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

/**
 * Una planilla de Excel para las pruebas, armada sobre la plantilla de verdad:
 * cada valor va con su tipo, como lo dejaria Excel (un texto, un numero, una
 * fecha). Un {@link YearMonth} es una fecha del dia 1, como el Mes de la plantilla.
 */
final class PlanillaDePrueba {

    private final List<Object[]> deudores = new ArrayList<>();
    private final List<Object[]> deudas = new ArrayList<>();

    /** RUT, Tipo, Nombre, Correo, Telefono. */
    PlanillaDePrueba deudor(Object... valores) {
        deudores.add(valores);
        return this;
    }

    /**
     * Id, RUT del deudor, Accion, Moneda, Concepto, Mes, Monto, Vencimiento y,
     * si vienen, Concepto del mes, Tasa, Motivo y Referencias.
     */
    PlanillaDePrueba deuda(Object... valores) {
        deudas.add(valores);
        return this;
    }

    byte[] bytes() {
        try (XSSFWorkbook libro = new XSSFWorkbook(new ByteArrayInputStream(PlantillaExcel.generar(30)));
             ByteArrayOutputStream salida = new ByteArrayOutputStream()) {
            escribir(libro, libro.getSheet(CarteraExcel.DEUDORES), deudores);
            escribir(libro, libro.getSheet(CarteraExcel.DEUDAS), deudas);
            libro.write(salida);
            return salida.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void escribir(XSSFWorkbook libro, Sheet hoja, List<Object[]> filas) {
        var fecha = libro.createCellStyle();
        fecha.setDataFormat(libro.createDataFormat().getFormat("dd-mm-yyyy"));
        for (int f = 0; f < filas.size(); f++) {
            Row fila = hoja.createRow(f + 1);
            Object[] valores = filas.get(f);
            for (int i = 0; i < valores.length; i++) {
                Object valor = valores[i];
                if (valor == null) {
                    continue;
                }
                Cell celda = fila.createCell(i);
                switch (valor) {
                    case Number n -> celda.setCellValue(n.doubleValue());
                    case LocalDate d -> {
                        celda.setCellValue(d);
                        celda.setCellStyle(fecha);
                    }
                    case YearMonth m -> {
                        celda.setCellValue(m.atDay(1));
                        celda.setCellStyle(fecha);
                    }
                    default -> celda.setCellValue(valor.toString());
                }
            }
        }
    }
}
