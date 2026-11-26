package com.tbridge.auth.service;

import com.tbridge.auth.exception.CorreoNoSalio;
import jakarta.mail.Address;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class MailLogsPrivacyTest {
    private final JavaMailSender smtp = mock(JavaMailSender.class);
    private final ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(MailService.class);
    private ch.qos.logback.classic.Level nivelAnterior;

    @org.junit.jupiter.api.BeforeEach
    void capturarTambienInfo() {
        nivelAnterior = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.INFO);
    }

    @org.junit.jupiter.api.AfterEach
    void restaurarNivel() {
        logger.setLevel(nivelAnterior);
    }

    @SuppressWarnings("unchecked")
    private MailService correo(String permitidos, String desvio, String host) {
        ObjectProvider<JavaMailSender> proveedor = mock(ObjectProvider.class);
        when(proveedor.getIfAvailable()).thenReturn(smtp);
        lenient().when(smtp.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
        return new MailService(proveedor, "persona@example.org", "http://localhost", host, 587,
                "clave-sintetica", "DataBridge", "", permitidos, desvio, "");
    }

    private void seguro(CapturedOutput salida) {
        String texto = salida.getAll();
        for (String correo : new String[]{"persona@example.org", "destino@example.net", "usuario@correo.cl"}) {
            assertFalse(texto.contains(correo), texto);
        }
        assertFalse(Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")
                .matcher(texto).find(), texto);
        assertFalse(texto.contains("clave-sintetica"));
    }

    @Test
    void arranque_cuenta_siete_sin_exponer_lista_remitente_ni_desvio(CapturedOutput salida) {
        String lista = IntStream.rangeClosed(1, 7).mapToObj(i -> "usuario" + i + "@example.org")
                .collect(Collectors.joining(","));
        correo(lista, "destino@example.net", "smtp.gmail.com");
        assertTrue(salida.getAll().contains("modo_prueba=activo; permitidos=7; desvio=activo"));
        for (String direccion : lista.split(",")) assertFalse(salida.getAll().contains(direccion));
        seguro(salida);
    }

    @Test
    void modo_desactivado_conserva_advertencia(CapturedOutput salida) {
        correo("", "", "smtp.gmail.com");
        assertTrue(salida.getAll().contains("SIN modo de prueba"));
        seguro(salida);
    }

    @Test
    void bloqueado_no_invoca_transporte(CapturedOutput salida) {
        correo("permitido@correo.cl", "", "smtp.gmail.com")
                .enviarCodigo("usuario@correo.cl", "ABC123", "Empresa");
        verify(smtp, never()).send(any(MimeMessage.class));
        assertTrue(salida.getAll().contains("destinatario no autorizado"));
        seguro(salida);
    }

    @Test
    void desvio_entrega_direccion_real_sin_registrarla(CapturedOutput salida) {
        correo("", "destino@example.net", "smtp.gmail.com")
                .enviarCodigo("usuario@correo.cl", "ABC123", "Empresa");
        var captor = org.mockito.ArgumentCaptor.forClass(MimeMessage.class);
        verify(smtp).send(captor.capture());
        var enviado = MailServiceTest.leer(captor.getValue());
        assertEquals(java.util.List.of("destino@example.net"), enviado.para());
        assertTrue(enviado.de().contains("persona@example.org"));
        assertTrue(enviado.texto().contains("ABC123"));
        assertTrue(salida.getAll().contains("se desvio al buzon configurado"));
        seguro(salida);
    }

    @Test
    void rechazo_smtp_anidado_conserva_clasificacion_y_codigo(CapturedOutput salida) throws Exception {
        var rechazo = new SendFailedException("550 Rejected recipient usuario@correo.cl clave-sintetica",
                null, null, null, new Address[]{new InternetAddress("usuario@correo.cl")});
        doThrow(new MailSendException(Map.of(new Object(), rechazo))).when(smtp).send(any(MimeMessage.class));
        var servicio = correo("", "", "smtp.gmail.com");
        var fallo = assertThrows(CorreoNoSalio.class,
                () -> servicio.enviarCodigo("usuario@correo.cl", "ABC123", "Empresa"));
        assertTrue(fallo.deLaDireccion());
        assertTrue(salida.getAll().contains("category=RECIPIENT_REJECTED smtp_code=550"));
        seguro(salida);
    }

    @Test
    void fallo_temporal_sigue_siendo_del_transporte_y_enlace_no_propaga(CapturedOutput salida) {
        doThrow(new MailSendException("451 Retry usuario@correo.cl")).when(smtp).send(any(MimeMessage.class));
        var servicio = correo("", "", "smtp.gmail.com");
        assertFalse(assertThrows(CorreoNoSalio.class,
                () -> servicio.enviarCodigo("usuario@correo.cl", "ABC123", "Empresa")).deLaDireccion());
        assertDoesNotThrow(() -> servicio.enviarEnlace("usuario@correo.cl", "http://localhost", 5));
        assertTrue(salida.getAll().contains("category=TRANSPORT_ERROR smtp_code=451"));
        seguro(salida);
    }

    @Test
    void permitido_se_entrega_y_sin_transporte_no_se_envia(CapturedOutput salida) {
        correo("usuario@correo.cl", "", "smtp.gmail.com")
                .enviarCodigo("usuario@correo.cl", "ABC123", "Empresa");
        var captor = org.mockito.ArgumentCaptor.forClass(MimeMessage.class);
        verify(smtp).send(captor.capture());
        assertEquals(java.util.List.of("usuario@correo.cl"), MailServiceTest.leer(captor.getValue()).para());
        correo("", "", "").enviarCodigo("usuario@correo.cl", "ABC123", "Empresa");
        verify(smtp, times(1)).send(any(MimeMessage.class));
        seguro(salida);
    }

    @Test
    void configuracion_y_aceptacion_desconocida_tienen_categoria_segura(CapturedOutput salida) {
        var servicio = correo("", "", "smtp.gmail.com");
        doThrow(new org.springframework.mail.MailAuthenticationException("535 persona@example.org"))
                .when(smtp).send(any(MimeMessage.class));
        assertFalse(assertThrows(CorreoNoSalio.class,
                () -> servicio.enviarCodigo("usuario@correo.cl", "ABC123", "Empresa")).deLaDireccion());
        assertTrue(salida.getAll().contains("category=CONFIGURATION_ERROR smtp_code=535"));
        doThrow(new MailSendException("Resend no respondió a tiempo; aceptación desconocida"))
                .when(smtp).send(any(MimeMessage.class));
        assertDoesNotThrow(() -> servicio.enviarEnlace("usuario@correo.cl", "http://localhost", 5));
        assertTrue(salida.getAll().contains("category=ACCEPTANCE_UNKNOWN"));
        seguro(salida);
    }

    @Test
    void dominio_reservado_sin_desvio_no_sale(CapturedOutput salida) {
        correo("", "", "smtp.gmail.com").enviarCodigo("persona@example.org", "ABC123", "Empresa");
        verify(smtp, never()).send(any(MimeMessage.class));
        assertTrue(salida.getAll().contains("Dominio reservado: el correo no sale"));
        seguro(salida);
    }
}
