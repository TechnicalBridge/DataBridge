package com.tbridge.debt.service;

import com.tbridge.debt.service.CarteraExcel.Columna;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.ClientAnchor;
import org.apache.poi.ss.usermodel.Comment;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationConstraint.OperatorType;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.Drawing;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

import static com.tbridge.debt.service.CarteraExcel.ACCION;
import static com.tbridge.debt.service.CarteraExcel.ACCIONES;
import static com.tbridge.debt.service.CarteraExcel.COLUMNAS_DEUDAS;
import static com.tbridge.debt.service.CarteraExcel.COLUMNAS_DEUDORES;
import static com.tbridge.debt.service.CarteraExcel.DEUDAS;
import static com.tbridge.debt.service.CarteraExcel.DEUDORES;
import static com.tbridge.debt.service.CarteraExcel.INSTRUCCIONES;
import static com.tbridge.debt.service.CarteraExcel.MES;
import static com.tbridge.debt.service.CarteraExcel.MONEDA;
import static com.tbridge.debt.service.CarteraExcel.MONEDAS;
import static com.tbridge.debt.service.CarteraExcel.MONTO;
import static com.tbridge.debt.service.CarteraExcel.MOTIVO;
import static com.tbridge.debt.service.CarteraExcel.MOTIVOS;
import static com.tbridge.debt.service.CarteraExcel.RUT;
import static com.tbridge.debt.service.CarteraExcel.RUT_DEUDOR;
import static com.tbridge.debt.service.CarteraExcel.TASA;
import static com.tbridge.debt.service.CarteraExcel.TIPO;
import static com.tbridge.debt.service.CarteraExcel.TIPOS;
import static com.tbridge.debt.service.CarteraExcel.VENCIMIENTO;

/**
 * La plantilla de la cartera en Excel (#201), lista para llenar: la hoja
 * <i>Instrucciones</i>, y las hojas <i>Deudores</i> y <i>Deudas</i> con sus
 * tipos de verdad. Las listas (tipo, accion, moneda, motivo) son desplegables,
 * el mes y el vencimiento son fechas, el monto y la tasa son numeros, y Excel
 * avisa si el RUT de una deuda no esta en <i>Deudores</i>. Cada encabezado
 * explica su columna al pasarle el mouse.
 *
 * <p>Va vacia a proposito: los ejemplos estan en <i>Instrucciones</i>, para que
 * nadie cargue un ejemplo por error.
 */
public final class PlantillaExcel {

    /** Las filas que quedan preparadas, con sus listas y sus formatos. */
    static final int FILAS = 5000;

    private static final byte[] VERDE = {(byte) 0x1F, (byte) 0x4E, (byte) 0x3D};
    private static final byte[] CREMA = {(byte) 0xF4, (byte) 0xEF, (byte) 0xE4};

    private PlantillaExcel() {
    }

    public static byte[] generar(int minDiasMora) {
        try (XSSFWorkbook libro = new XSSFWorkbook(); ByteArrayOutputStream salida = new ByteArrayOutputStream()) {
            Estilos estilos = new Estilos(libro);
            instrucciones(libro.createSheet(INSTRUCCIONES), estilos, minDiasMora);
            Sheet deudores = hojaDeDatos(libro.createSheet(DEUDORES), COLUMNAS_DEUDORES, estilos);
            Sheet deudas = hojaDeDatos(libro.createSheet(DEUDAS), COLUMNAS_DEUDAS, estilos);
            validaciones(deudores, deudas);
            libro.setActiveSheet(0);
            libro.write(salida);
            return salida.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ------------------------------------------------------------------
    //  Las hojas de datos
    // ------------------------------------------------------------------

    private static Sheet hojaDeDatos(Sheet hoja, List<Columna> columnas, Estilos estilos) {
        Row encabezado = hoja.createRow(0);
        encabezado.setHeightInPoints(30);
        Drawing<?> dibujo = hoja.createDrawingPatriarch();
        for (int i = 0; i < columnas.size(); i++) {
            Columna columna = columnas.get(i);
            Cell celda = encabezado.createCell(i);
            celda.setCellValue(columna.titulo());
            celda.setCellStyle(estilos.encabezado);
            celda.setCellComment(nota(hoja, dibujo, i, columna));
            hoja.setDefaultColumnStyle(i, estilos.de(columna));
            hoja.setColumnWidth(i, ancho(columna) * 256);
        }
        hoja.createFreezePane(0, 1);
        hoja.setAutoFilter(new CellRangeAddress(0, 0, 0, columnas.size() - 1));
        return hoja;
    }

    private static Comment nota(Sheet hoja, Drawing<?> dibujo, int columna, Columna c) {
        ClientAnchor donde = hoja.getWorkbook().getCreationHelper().createClientAnchor();
        donde.setCol1(columna);
        donde.setCol2(columna + 3);
        donde.setRow1(1);
        donde.setRow2(6);
        Comment nota = dibujo.createCellComment(donde);
        nota.setString(hoja.getWorkbook().getCreationHelper().createRichTextString(
                c.tipo() + " · obligatoria: " + c.obligatoria().toLowerCase() + ". " + c.explicacion()
                        + " Ejemplo: " + c.ejemplo()));
        nota.setAuthor("DataBridge");
        return nota;
    }

    private static int ancho(Columna c) {
        return Math.max(14, Math.min(40, Math.max(c.titulo().length(), c.ejemplo().length()) + 4));
    }

    private static void validaciones(Sheet deudores, Sheet deudas) {
        lista(deudores, COLUMNAS_DEUDORES.indexOf(TIPO), TIPOS, TIPO);
        lista(deudas, COLUMNAS_DEUDAS.indexOf(ACCION), ACCIONES, ACCION);
        lista(deudas, COLUMNAS_DEUDAS.indexOf(MONEDA), MONEDAS, MONEDA);
        lista(deudas, COLUMNAS_DEUDAS.indexOf(MOTIVO), MOTIVOS, MOTIVO);

        DataValidationHelper ayuda = deudas.getDataValidationHelper();
        for (Columna fecha : List.of(MES, VENCIMIENTO)) {
            agregar(deudas, ayuda.createDateConstraint(OperatorType.BETWEEN, "DATE(2000,1,1)", "DATE(2100,12,31)",
                    null), COLUMNAS_DEUDAS.indexOf(fecha), fecha, DataValidation.ErrorStyle.STOP,
                    "Escríbelo como fecha, por ejemplo " + fecha.ejemplo() + ".");
        }
        agregar(deudas, ayuda.createDecimalConstraint(OperatorType.GREATER_THAN, "0", null),
                COLUMNAS_DEUDAS.indexOf(MONTO), MONTO, DataValidation.ErrorStyle.STOP,
                "Un número mayor que cero, sin puntos de miles: 350000, o 38,5 en UF.");
        agregar(deudas, ayuda.createDecimalConstraint(OperatorType.BETWEEN, "0", "100"),
                COLUMNAS_DEUDAS.indexOf(TASA), TASA, DataValidation.ErrorStyle.STOP,
                "El porcentaje mensual, como 1,5. Vacío si no hay interés.");

        //  El RUT de una deuda tiene que estar en Deudores. Es un aviso, no un
        //  bloqueo: con otro formato (puntos o no) Excel no lo encuentra, y
        //  DataBridge si.
        int rut = COLUMNAS_DEUDAS.indexOf(RUT_DEUDOR);
        String celda = CellReference.convertNumToColString(rut) + "2";
        String rangoDeudores = DEUDORES + "!$" + CellReference.convertNumToColString(COLUMNAS_DEUDORES.indexOf(RUT))
                + "$2:$" + CellReference.convertNumToColString(COLUMNAS_DEUDORES.indexOf(RUT)) + "$" + (FILAS + 1);
        agregar(deudas, ayuda.createCustomConstraint("COUNTIF(" + rangoDeudores + "," + celda + ")>0"), rut,
                RUT_DEUDOR, DataValidation.ErrorStyle.WARNING,
                "Ese RUT no está en la hoja Deudores: agrégalo ahí antes.");
    }

    private static void lista(Sheet hoja, int columna, List<String> valores, Columna c) {
        DataValidationHelper ayuda = hoja.getDataValidationHelper();
        agregar(hoja, ayuda.createExplicitListConstraint(valores.toArray(String[]::new)), columna, c,
                DataValidation.ErrorStyle.STOP, "Elige uno de la lista: " + String.join(", ", valores) + ".");
    }

    private static void agregar(Sheet hoja, DataValidationConstraint regla, int columna, Columna c,
                                int estiloDeError, String error) {
        DataValidationHelper ayuda = hoja.getDataValidationHelper();
        DataValidation validacion = ayuda.createValidation(regla, new CellRangeAddressList(1, FILAS, columna, columna));
        validacion.setErrorStyle(estiloDeError);
        validacion.setShowErrorBox(true);
        validacion.createErrorBox(c.titulo(), error);
        validacion.setShowPromptBox(true);
        validacion.createPromptBox(c.titulo(), recortar(c.explicacion(), 250));
        validacion.setEmptyCellAllowed(true);
        hoja.addValidationData(validacion);
    }

    /** Excel no acepta mensajes de mas de 255 caracteres en una validacion. */
    private static String recortar(String texto, int maximo) {
        return texto.length() <= maximo ? texto : texto.substring(0, maximo - 1) + "…";
    }

    // ------------------------------------------------------------------
    //  Las instrucciones
    // ------------------------------------------------------------------

    private static void instrucciones(Sheet hoja, Estilos estilos, int minDiasMora) {
        hoja.setColumnWidth(0, 26 * 256);
        hoja.setColumnWidth(1, 20 * 256);
        hoja.setColumnWidth(2, 20 * 256);
        hoja.setColumnWidth(3, 30 * 256);
        hoja.setColumnWidth(4, 70 * 256);
        int[] n = {0};
        linea(hoja, n, "La planilla de la cartera de DataBridge", estilos.titulo);
        linea(hoja, n, "Llena la hoja Deudores (una fila por persona) y la hoja Deudas (una fila por cada mes que se "
                + "debe). Después súbela en Cargar cartera, dentro de una campaña: antes de cargar, DataBridge te "
                + "muestra qué entraría, qué no y por qué, sin guardar nada.", estilos.parrafo);
        n[0]++;
        linea(hoja, n, "Cómo se llena", estilos.subtitulo);
        for (String paso : List.of(
                "1. En Deudores, una fila por cada persona o empresa que debe, con su RUT y su correo.",
                "2. En Deudas, una fila por cada mes impago, con el RUT de su deudor. Quien debe tres meses de "
                        + "arriendo lleva tres filas, con el mismo Id de la deuda.",
                "3. Un cliente al día: una fila con la acción «al día», sin mes, monto ni vencimiento. Si DataBridge "
                        + "lo estaba cobrando, la deuda se cierra porque pagó directo.",
                "4. Para sacar una deuda de la cobranza: una fila con la acción «retirar» y el motivo.",
                "5. Solo entran deudores morosos: una deuda entra cuando su mes impago más antiguo lleva al menos "
                        + minDiasMora + " días vencido. Las demás se rechazan, con su motivo.",
                "6. Los montos van como número, sin puntos de miles: 350000, o 38,5 en UF. Las fechas, como fecha.",
                "7. No le cambies los nombres a las hojas ni a los encabezados: DataBridge los busca por su nombre.")) {
            linea(hoja, n, paso, estilos.parrafo);
        }
        n[0]++;
        tablaDeColumnas(hoja, n, "Las columnas de Deudores", COLUMNAS_DEUDORES, estilos);
        n[0]++;
        tablaDeColumnas(hoja, n, "Las columnas de Deudas", COLUMNAS_DEUDAS, estilos);
        n[0]++;
        linea(hoja, n, "Un ejemplo", estilos.subtitulo);
        linea(hoja, n, "Felipe debe julio, agosto y septiembre de su arriendo; Comercial Ñandú está al día; y la "
                + "deuda CTR-2025-031 se retira porque el cliente pagó en la oficina.", estilos.parrafo);
        n[0]++;
        tabla(hoja, n, "Deudores", List.of(
                List.of("RUT", "Tipo", "Nombre", "Correo", "Teléfono"),
                List.of("16.482.337-7", "persona", "Felipe Rojas Muñoz", "felipe.rojas@correo.cl", "+56 9 4412 0387"),
                List.of("76.991.245-2", "empresa", "Comercial Ñandú SpA", "pagos@nandu.cl", "")), estilos);
        n[0]++;
        tabla(hoja, n, "Deudas", List.of(
                List.of("Id de la deuda", "RUT del deudor", "Acción", "Moneda", "Concepto de la deuda", "Mes",
                        "Monto", "Vencimiento"),
                List.of("CTR-2026-014", "16.482.337-7", "registrar", "CLP", "Arriendo Depto. 302", "jul-2026",
                        "350000", "05-07-2026"),
                List.of("CTR-2026-014", "16.482.337-7", "registrar", "CLP", "Arriendo Depto. 302", "ago-2026",
                        "350000", "05-08-2026"),
                List.of("CTR-2026-014", "16.482.337-7", "registrar", "CLP", "Arriendo Depto. 302", "sept-2026",
                        "350000", "05-09-2026"),
                List.of("CTR-2026-022", "76.991.245-2", "al día", "UF", "Arriendo Local 12", "", "", ""),
                List.of("CTR-2025-031", "", "retirar", "", "", "", "", "")), estilos);
        linea(hoja, n, "En el retiro, la columna Motivo del retiro dice pago_directo.", estilos.nota);
    }

    private static void tablaDeColumnas(Sheet hoja, int[] n, String titulo, List<Columna> columnas, Estilos estilos) {
        linea(hoja, n, titulo, estilos.subtitulo);
        Row encabezado = hoja.createRow(n[0]++);
        String[] nombres = {"Columna", "Tipo", "Obligatoria", "Ejemplo", "Qué es"};
        for (int i = 0; i < nombres.length; i++) {
            celda(encabezado, i, nombres[i], estilos.encabezado);
        }
        for (Columna c : columnas) {
            Row fila = hoja.createRow(n[0]++);
            celda(fila, 0, c.titulo(), estilos.negrita);
            celda(fila, 1, c.tipo(), estilos.parrafo);
            celda(fila, 2, c.obligatoria(), estilos.parrafo);
            celda(fila, 3, c.ejemplo(), estilos.parrafo);
            celda(fila, 4, c.explicacion(), estilos.parrafo);
        }
    }

    private static void tabla(Sheet hoja, int[] n, String titulo, List<List<String>> filas, Estilos estilos) {
        linea(hoja, n, "Hoja " + titulo, estilos.negrita);
        for (int f = 0; f < filas.size(); f++) {
            Row fila = hoja.createRow(n[0]++);
            for (int i = 0; i < filas.get(f).size(); i++) {
                celda(fila, i, filas.get(f).get(i), f == 0 ? estilos.encabezado : estilos.ejemplo);
            }
        }
    }

    private static void linea(Sheet hoja, int[] n, String texto, CellStyle estilo) {
        Row fila = hoja.createRow(n[0]++);
        celda(fila, 0, texto, estilo);
        if (estilo != null && estilo.getWrapText()) {
            hoja.addMergedRegion(new CellRangeAddress(fila.getRowNum(), fila.getRowNum(), 0, 4));
            fila.setHeightInPoints(Math.max(15, 15 * (1 + texto.length() / 150f)));
        }
    }

    private static void celda(Row fila, int columna, String texto, CellStyle estilo) {
        Cell celda = fila.createCell(columna);
        celda.setCellValue(texto);
        if (estilo != null) {
            celda.setCellStyle(estilo);
        }
    }

    /** Los estilos de la plantilla, creados una sola vez: Excel tiene un tope de estilos por libro. */
    private static final class Estilos {

        final CellStyle encabezado;
        final CellStyle titulo;
        final CellStyle subtitulo;
        final CellStyle parrafo;
        final CellStyle negrita;
        final CellStyle nota;
        final CellStyle ejemplo;
        final CellStyle texto;
        final CellStyle fecha;
        final CellStyle mes;
        final CellStyle numero;

        Estilos(XSSFWorkbook libro) {
            short formatoTexto = libro.createDataFormat().getFormat("@");

            XSSFCellStyle cab = libro.createCellStyle();
            Font blanca = libro.createFont();
            blanca.setBold(true);
            blanca.setColor(org.apache.poi.ss.usermodel.IndexedColors.WHITE.getIndex());
            cab.setFont(blanca);
            cab.setFillForegroundColor(new XSSFColor(VERDE, null));
            cab.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            cab.setVerticalAlignment(VerticalAlignment.CENTER);
            cab.setWrapText(true);
            cab.setBorderBottom(BorderStyle.THIN);
            encabezado = cab;

            titulo = conFuente(libro, 16, true, false);
            subtitulo = conFuente(libro, 12, true, false);
            negrita = conFuente(libro, 11, true, false);
            parrafo = conFuente(libro, 11, false, true);
            parrafo.setVerticalAlignment(VerticalAlignment.TOP);
            nota = conFuente(libro, 10, false, false);

            XSSFCellStyle ej = libro.createCellStyle();
            ej.setFillForegroundColor(new XSSFColor(CREMA, null));
            ej.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            ejemplo = ej;

            texto = libro.createCellStyle();
            texto.setDataFormat(formatoTexto);
            fecha = libro.createCellStyle();
            fecha.setDataFormat(libro.createDataFormat().getFormat("dd-mm-yyyy"));
            mes = libro.createCellStyle();
            mes.setDataFormat(libro.createDataFormat().getFormat("mmm-yyyy"));
            numero = libro.createCellStyle();
            numero.setDataFormat(libro.createDataFormat().getFormat("General"));
        }

        CellStyle de(Columna c) {
            if (c == MES) return mes;
            if (c == VENCIMIENTO) return fecha;
            if (c == MONTO || c == TASA) return numero;
            return texto;
        }

        private static CellStyle conFuente(XSSFWorkbook libro, int puntos, boolean negrita, boolean ajustar) {
            CellStyle estilo = libro.createCellStyle();
            Font fuente = libro.createFont();
            fuente.setFontHeightInPoints((short) puntos);
            fuente.setBold(negrita);
            estilo.setFont(fuente);
            estilo.setWrapText(ajustar);
            return estilo;
        }
    }
}
