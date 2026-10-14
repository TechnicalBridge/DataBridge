package com.tbridge.auth.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


@ExtendWith(MockitoExtension.class)
class MailServiceTest {

    @Mock
    private JavaMailSender smtp;

    @Mock
    private ObjectProvider<JavaMailSender> proveedor;

    private MailService correo(String host) {
        when(proveedor.getIfAvailable()).thenReturn(smtp);
        return new MailService(proveedor, "noreply@technicalbridge.local", "http://localhost:8080/", host);
    }

    @Test
    void el_correo_del_codigo_no_trae_ningun_enlace() {
        correo("mailpit").enviarCodigo("felipe.rojas@correo.cl", "K7M2QX", "Patrimonio Inmuebles");

        ArgumentCaptor<SimpleMailMessage> enviado = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(smtp).send(enviado.capture());
        String texto = enviado.getValue().getText();
        assertTrue(texto.contains("K7M2QX"));
        assertTrue(texto.contains("Patrimonio Inmuebles"));
        //  La direccion va escrita, pero nada que se pueda cliquear hacia otro lado.
        assertFalse(texto.contains("?token="));
    }

    @Test
    void el_recordatorio_dice_la_fecha_pero_no_el_monto_ni_un_enlace() {
        correo("mailpit").enviarRecordatorio("felipe.rojas@correo.cl", "P4R8TW", "Patrimonio Inmuebles",
                LocalDate.of(2026, 10, 20));

        ArgumentCaptor<SimpleMailMessage> enviado = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(smtp).send(enviado.capture());
        String texto = enviado.getValue().getText();
        assertTrue(texto.contains("20 de octubre"), texto);
        assertTrue(texto.contains("P4R8TW"));
        assertTrue(texto.contains("Patrimonio Inmuebles"));
        //  Un correo que llega a quien no es no dice cuanto debe nadie.
        assertFalse(texto.contains("$"));
        assertFalse(texto.contains("?token="));
    }

    @Test
    void la_invitacion_trae_el_enlace_cuanto_dura_y_por_donde_entrar_despues() {
        correo("mailpit").enviarInvitacion("ana.soto@empresatest.cl", "Ana Soto",
                "al portal de empresas, como parte de Empresa Test", "http://localhost:8080/magic?token=abc", 24,
                "http://localhost:8080/databridge/login");

        ArgumentCaptor<SimpleMailMessage> enviado = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(smtp).send(enviado.capture());
        String texto = enviado.getValue().getText();
        assertTrue(texto.contains("Hola Ana Soto"), texto);
        assertTrue(texto.contains("como parte de Empresa Test"));
        assertTrue(texto.contains("http://localhost:8080/magic?token=abc"));
        assertTrue(texto.contains("24 horas"));
        //  Si el enlace vencio, la persona sabe por donde pedir otro.
        assertTrue(texto.contains("http://localhost:8080/databridge/login"));
        assertTrue(texto.contains("No hay") && texto.contains("contrasena"));
    }

    @Test
    void sin_smtp_no_intenta_mandar_nada() {
        correo("").enviarCodigo("felipe.rojas@correo.cl", "K7M2QX", null);
        verify(smtp, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void si_el_smtp_falla_el_acceso_no_se_cae() {
        MailService servicio = correo("mailpit");
        doThrow(new MailSendException("sin conexion")).when(smtp).send(any(SimpleMailMessage.class));

        assertDoesNotThrow(() -> servicio.enviarEnlace("camila.reyes@apofyx.cl", "http://localhost:8080/magic?token=x", 15));
    }
}
