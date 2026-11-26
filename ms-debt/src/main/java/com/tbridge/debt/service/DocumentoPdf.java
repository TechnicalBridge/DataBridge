package com.tbridge.debt.service;

import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import com.tbridge.debt.model.Debt;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * El aspecto de los papeles que emite DataBridge: el certificado de deuda
 * pagada y el comprobante de cada pago.
 *
 * <p>Los mismos colores del portal (verde bosque sobre fondo claro) y la misma
 * forma de escribir montos y fechas, para que el papel se reconozca como parte
 * del mismo lugar.
 */
final class DocumentoPdf {

    private static final ZoneId CHILE = ZoneId.of("America/Santiago");
    private static final Locale ES_CL = Locale.forLanguageTag("es-CL");
    private static final Color VERDE = new Color(62, 123, 79);
    private static final Color TINTA = new Color(46, 42, 36);
    private static final Color GRIS = new Color(124, 114, 102);
    private static final Color LINEA = new Color(221, 210, 190);

    private static final Font MARCA = new Font(Font.HELVETICA, 14, Font.BOLD, TINTA);
    private static final Font TITULO = new Font(Font.HELVETICA, 22, Font.BOLD, TINTA);
    private static final Font TEXTO = new Font(Font.HELVETICA, 11, Font.NORMAL, TINTA);
    private static final Font ETIQUETA = new Font(Font.HELVETICA, 9, Font.NORMAL, GRIS);
    private static final Font VALOR = new Font(Font.HELVETICA, 11, Font.BOLD, TINTA);
    private static final Font GRANDE = new Font(Font.HELVETICA, 26, Font.BOLD, VERDE);
    private static final Font NOTA = new Font(Font.HELVETICA, 9, Font.NORMAL, GRIS);

    /** El simbolo de DataBridge: el mismo {@code logo.png} del portal. */
    private static final byte[] LOGO = leerLogo();

    private final Document documento = new Document(PageSize.LETTER, 64, 64, 64, 56);

    private static byte[] leerLogo() {
        try (var entrada = DocumentoPdf.class.getResourceAsStream("/documentos/logo.png")) {
            if (entrada == null) {
                throw new IllegalStateException("Falta documentos/logo.png en ms-debt");
            }
            return entrada.readAllBytes();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("No se pudo leer documentos/logo.png", e);
        }
    }
    private final ByteArrayOutputStream salida = new ByteArrayOutputStream();

    DocumentoPdf() {
        PdfWriter.getInstance(documento, salida);
        documento.open();
    }

    /**
     * Arriba, el simbolo y el nombre, como en el portal y en los correos (#541).
     * El nombre es texto: se lee igual si el visor no muestra la imagen.
     */
    DocumentoPdf encabezado(String titulo, String bajada) {
        PdfPTable marca = new PdfPTable(new float[]{26, 300});
        marca.setTotalWidth(326);
        marca.setLockedWidth(true);
        marca.setHorizontalAlignment(Element.ALIGN_LEFT);
        Image simbolo;
        try {
            simbolo = Image.getInstance(LOGO);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo leer el simbolo de DataBridge", e);
        }
        simbolo.scaleToFit(22, 22);
        PdfPCell conSimbolo = new PdfPCell(simbolo, false);
        conSimbolo.setBorder(Rectangle.NO_BORDER);
        conSimbolo.setVerticalAlignment(Element.ALIGN_MIDDLE);
        conSimbolo.setPadding(0);
        marca.addCell(conSimbolo);
        PdfPCell nombre = new PdfPCell(new Phrase("DataBridge", MARCA));
        nombre.setBorder(Rectangle.NO_BORDER);
        nombre.setVerticalAlignment(Element.ALIGN_MIDDLE);
        nombre.setPaddingLeft(4);
        nombre.setPaddingBottom(4);
        marca.addCell(nombre);
        documento.add(marca);
        Paragraph encabezado = new Paragraph(titulo, TITULO);
        encabezado.setSpacingBefore(10);
        documento.add(encabezado);
        Paragraph debajo = new Paragraph(bajada, NOTA);
        debajo.setSpacingAfter(22);
        documento.add(debajo);
        return this;
    }

    DocumentoPdf parrafo(String texto) {
        Paragraph parrafo = new Paragraph(texto, TEXTO);
        parrafo.setLeading(16);
        parrafo.setSpacingAfter(16);
        documento.add(parrafo);
        return this;
    }

    /** El numero que importa, grande: lo que se pago o lo que se debia. */
    DocumentoPdf destacado(String etiqueta, String valor, String nota) {
        documento.add(new Paragraph(etiqueta, ETIQUETA));
        Paragraph grande = new Paragraph(valor, GRANDE);
        grande.setSpacingAfter(nota == null ? 18 : 2);
        documento.add(grande);
        if (nota != null) {
            Paragraph debajo = new Paragraph(nota, NOTA);
            debajo.setSpacingAfter(18);
            documento.add(debajo);
        }
        return this;
    }

    /** Una tabla de dos columnas, etiqueta y valor, con una linea entre filas. */
    DocumentoPdf datos(String[][] filas) {
        PdfPTable tabla = new PdfPTable(new float[]{1.1f, 2.4f});
        tabla.setWidthPercentage(100);
        for (String[] fila : filas) {
            tabla.addCell(celda(new Phrase(fila[0], ETIQUETA)));
            tabla.addCell(celda(new Phrase(fila[1], VALOR)));
        }
        tabla.setSpacingAfter(22);
        documento.add(tabla);
        return this;
    }

    DocumentoPdf pie(String texto) {
        Paragraph pie = new Paragraph(texto, NOTA);
        pie.setLeading(13);
        pie.setSpacingBefore(10);
        pie.setAlignment(Element.ALIGN_LEFT);
        documento.add(pie);
        return this;
    }

    byte[] cerrar() {
        documento.close();
        return salida.toByteArray();
    }

    private static PdfPCell celda(Phrase contenido) {
        PdfPCell celda = new PdfPCell(contenido);
        celda.setBorder(Rectangle.BOTTOM);
        celda.setBorderColor(LINEA);
        celda.setPaddingTop(7);
        celda.setPaddingBottom(8);
        return celda;
    }

    // ------------------------------------------------------------------
    //  Como se escriben las cosas en Chile
    // ------------------------------------------------------------------

    /** $1.040.000 o UF 115,50: cada monto en su moneda. */
    static String dinero(BigDecimal monto, Debt.Currency moneda) {
        if (moneda == Debt.Currency.UF) {
            return "UF " + formato("#,##0.00").format(monto.setScale(2, RoundingMode.HALF_UP));
        }
        return pesos(monto.setScale(0, RoundingMode.HALF_UP).longValue());
    }

    static String pesos(long monto) {
        return "$" + formato("#,##0").format(monto);
    }

    static String pesosConDecimales(BigDecimal monto) {
        return "$" + formato("#,##0.00").format(monto);
    }

    /** 16482337-7 -> 16.482.337-7. */
    static String rut(String rut) {
        if (rut == null || !rut.contains("-")) {
            return rut;
        }
        String[] partes = rut.split("-");
        return formato("#,##0").format(Long.parseLong(partes[0])) + "-" + partes[1];
    }

    static String fecha(LocalDate dia) {
        return dia.format(DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", ES_CL));
    }

    static String fechaYHora(Instant momento) {
        return momento.atZone(CHILE).format(DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy, HH:mm", ES_CL));
    }

    static LocalDate hoy() {
        return LocalDate.now(CHILE);
    }

    private static DecimalFormat formato(String patron) {
        DecimalFormatSymbols simbolos = new DecimalFormatSymbols(ES_CL);
        simbolos.setGroupingSeparator('.');
        simbolos.setDecimalSeparator(',');
        return new DecimalFormat(patron, simbolos);
    }
}
