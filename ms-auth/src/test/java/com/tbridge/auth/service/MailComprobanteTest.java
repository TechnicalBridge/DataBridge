package com.tbridge.auth.service;

import com.tbridge.auth.exception.CorreoNoSalio;
import jakarta.mail.BodyPart;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** El correo del comprobante de un pago (#541): el detalle, sin enlaces, y los PDF adjuntos. */
@ExtendWith(MockitoExtension.class)
class MailComprobanteTest {

    private static final byte[] COMPROBANTE = "%PDF-1.4 comprobante".getBytes();
    private static final byte[] CERTIFICADO = "%PDF-1.4 certificado".getBytes();
    private static final List<MailService.Fila> FILAS = List.of(
            new MailService.Fila("Acreedor", "Patrimonio Inmuebles, RUT 76.543.210-K"),
            new MailService.Fila("Qué se pagó", "Cuota 3 de 6"),
            new MailService.Fila("Medio de pago", "Webpay, operación wp-9f31c2"));

    @Mock
    private JavaMailSender smtp;

    @Mock
    private JavaMailSender buzon;

    @Mock
    private ObjectProvider<JavaMailSender> proveedor;

    @BeforeEach
    void armarEnMemoria() {
        lenient().when(smtp.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
        lenient().when(buzon.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
        when(proveedor.getIfAvailable()).thenReturn(smtp);
    }

    /** Por Gmail, con el modo de prueba que se pida y Mailpit para los dominios reservados. */
    private MailService real(String soloA) {
        return new MailService(proveedor, "equipo@gmail.com", "http://localhost:8080/", "smtp.gmail.com", 587,
                "abcd efgh ijkl mnop", "DataBridge", "", soloA, "", "mailpit:1025", "", 100, buzon,
                java.time.Clock.systemUTC());
    }

    private static void cuota(MailService correo, String para) {
        correo.enviarComprobante(para, "57", "$346.666", null, "Patrimonio Inmuebles", FILAS, false, "$693.332",
                List.of(new MailService.Adjunto("comprobante-57.pdf", COMPROBANTE)));
    }

    /** Lo que sale: las partes del correo, de afuera hacia adentro, y los adjuntos. */
    record Leido(String asunto, String tipo, String texto, String html, List<String> incrustadas,
                 List<MimeBodyPart> adjuntos) {
    }

    private static Leido leer(MimeMessage mensaje) throws Exception {
        mensaje.saveChanges();
        String[] versiones = new String[2];
        List<String> incrustadas = new ArrayList<>();
        List<MimeBodyPart> adjuntos = new ArrayList<>();
        recorrer(mensaje, versiones, incrustadas, adjuntos);
        return new Leido(mensaje.getSubject(), mensaje.getContentType().split(";")[0], versiones[0], versiones[1],
                incrustadas, adjuntos);
    }

    private static void recorrer(Part parte, String[] versiones, List<String> incrustadas,
                                 List<MimeBodyPart> adjuntos) throws Exception {
        if (parte.getContent() instanceof Multipart partes) {
            for (int i = 0; i < partes.getCount(); i++) {
                recorrer(partes.getBodyPart(i), versiones, incrustadas, adjuntos);
            }
        } else if (Part.ATTACHMENT.equalsIgnoreCase(parte.getDisposition())) {
            adjuntos.add((MimeBodyPart) parte);
        } else if (parte instanceof MimeBodyPart mime && mime.getContentID() != null) {
            incrustadas.add(mime.getContentID());
        } else if (parte.isMimeType("text/plain")) {
            versiones[0] = (String) parte.getContent();
        } else if (parte.isMimeType("text/html")) {
            versiones[1] = (String) parte.getContent();
        }
    }

    private Leido enviadoPor(JavaMailSender servidor) throws Exception {
        ArgumentCaptor<MimeMessage> mensaje = ArgumentCaptor.forClass(MimeMessage.class);
        verify(servidor).send(mensaje.capture());
        return leer(mensaje.getValue());
    }

    @Test
    void el_comprobante_de_una_cuota_trae_el_detalle_y_el_pdf_adjunto() throws Exception {
        cuota(real(""), "felipe.rojas@correo.cl");

        Leido correo = enviadoPor(smtp);
        assertEquals("Recibimos tu pago", correo.asunto());
        //  El correo y, aparte, los adjuntos.
        assertEquals("multipart/mixed", correo.tipo());
        for (String parte : new String[]{correo.texto(), correo.html()}) {
            assertTrue(parte.contains("$346.666"), parte);
            assertTrue(parte.contains("57"));
            assertTrue(parte.contains("Cuota 3 de 6"));
            assertTrue(parte.contains("Webpay, operación wp-9f31c2"));
            assertTrue(parte.contains("Queda por pagar"));
            assertTrue(parte.contains("$693.332"));
            assertTrue(parte.contains("Te adjuntamos este comprobante en PDF"));
            assertFalse(parte.contains("certificado de deuda pagada"));
        }
        //  Ningun enlace, como todos los correos de cobranza.
        assertFalse(correo.html().contains("<a "));
        assertFalse(correo.texto().contains("http"));
        //  El simbolo sigue dentro del correo (#465).
        assertEquals(List.of("<" + MailService.LOGO_ID + ">"), correo.incrustadas());

        assertEquals(1, correo.adjuntos().size());
        MimeBodyPart pdf = correo.adjuntos().getFirst();
        assertEquals("comprobante-57.pdf", pdf.getFileName());
        assertTrue(pdf.isMimeType("application/pdf"));
        assertArrayEquals(COMPROBANTE, pdf.getInputStream().readAllBytes());
    }

    @Test
    void si_la_deuda_quedo_pagada_lo_dice_y_adjunta_tambien_el_certificado() throws Exception {
        real("").enviarComprobante("felipe.rojas@correo.cl", "58", "$693.332", null, "Patrimonio Inmuebles", FILAS,
                true, "$0", List.of(new MailService.Adjunto("comprobante-58.pdf", COMPROBANTE),
                        new MailService.Adjunto("certificado-8.pdf", CERTIFICADO)));

        Leido correo = enviadoPor(smtp);
        assertEquals("Tu deuda quedó pagada", correo.asunto());
        for (String parte : new String[]{correo.texto(), correo.html()}) {
            assertTrue(parte.contains("quedó pagada por completo"), parte);
            assertTrue(parte.contains("certificado de deuda pagada"));
            //  Pagada, no se dice cuanto queda.
            assertFalse(parte.contains("Queda por pagar"));
        }
        assertEquals(List.of("comprobante-58.pdf", "certificado-8.pdf"),
                correo.adjuntos().stream().map(MailComprobanteTest::nombre).toList());
        assertArrayEquals(CERTIFICADO, correo.adjuntos().get(1).getInputStream().readAllBytes());
    }

    @Test
    void en_uf_dice_cuantos_pesos_fueron() throws Exception {
        real("").enviarComprobante("felipe.rojas@correo.cl", "59", "UF 38,50", "Equivale a $1.535.246",
                "Patrimonio Inmuebles", FILAS, false, "UF 77,00",
                List.of(new MailService.Adjunto("comprobante-59.pdf", COMPROBANTE)));

        Leido correo = enviadoPor(smtp);
        assertTrue(correo.html().contains("UF 38,50"));
        assertTrue(correo.html().contains("Equivale a $1.535.246"));
        assertTrue(correo.texto().contains("Equivale a $1.535.246"));
    }

    @Test
    void lo_que_escribe_la_empresa_no_entra_como_html() throws Exception {
        real("").enviarComprobante("felipe.rojas@correo.cl", "57", "$346.666", null,
                "<a href=\"https://phishing.example\">Paga aqui</a>", FILAS, false, "$1",
                List.of(new MailService.Adjunto("comprobante-57.pdf", COMPROBANTE)));

        assertFalse(enviadoPor(smtp).html().contains("<a href"));
    }

    @Test
    void si_el_servidor_no_lo_acepta_lo_dice() {
        MailService correo = real("");
        doThrow(new MailSendException("451 4.3.0 Mail server temporarily rejected message")).when(smtp)
                .send(any(MimeMessage.class));

        CorreoNoSalio noSalio = assertThrows(CorreoNoSalio.class, () -> cuota(correo, "felipe.rojas@correo.cl"));
        assertFalse(noSalio.deLaDireccion());
    }

    @Test
    void respeta_el_modo_de_prueba() {
        cuota(real("equipo@gmail.com"), "felipe.rojas@correo.cl");

        verify(smtp, never()).send(any(MimeMessage.class));
    }

    @Test
    void lo_de_un_dominio_reservado_va_al_buzon_de_prueba_con_sus_adjuntos() throws Exception {
        cuota(real(""), "deudor@example.com");

        verify(smtp, never()).send(any(MimeMessage.class));
        Leido correo = enviadoPor(buzon);
        assertEquals(1, correo.adjuntos().size());
        assertEquals("comprobante-57.pdf", nombre(correo.adjuntos().getFirst()));
    }

    private static String nombre(BodyPart parte) {
        try {
            return parte.getFileName();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
