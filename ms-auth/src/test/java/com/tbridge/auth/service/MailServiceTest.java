package com.tbridge.auth.service;

import com.tbridge.auth.dto.response.PruebaDeCorreoResponse;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /** Con un servidor real: una cuenta que manda, su nombre, y el modo de prueba que se pida. */
    private MailService real(String soloA, String desviarA) {
        when(proveedor.getIfAvailable()).thenReturn(smtp);
        return new MailService(proveedor, "equipo@gmail.com", "http://localhost:8080/", "smtp.gmail.com", 587,
                "abcd efgh ijkl mnop", "DataBridge", "", soloA, desviarA, "mailpit:1025");
    }

    private SimpleMailMessage enviado() {
        ArgumentCaptor<SimpleMailMessage> mensaje = ArgumentCaptor.forClass(SimpleMailMessage.class);
        verify(smtp).send(mensaje.capture());
        return mensaje.getValue();
    }

    // ------------------------------------------------------------------
    //  Un servidor real
    // ------------------------------------------------------------------

    @Test
    void el_remitente_sale_con_su_nombre_para_que_el_buzon_diga_quien_escribe() {
        real("", "").enviarCodigo("felipe.rojas@correo.cl", "K7M2QX", "Patrimonio Inmuebles");

        assertEquals("DataBridge <equipo@gmail.com>", enviado().getFrom());
    }

    @Test
    void con_responder_a_las_respuestas_no_van_a_la_cuenta_que_manda() {
        when(proveedor.getIfAvailable()).thenReturn(smtp);
        MailService servicio = new MailService(proveedor, "equipo@gmail.com", "http://localhost:8080/",
                "smtp.gmail.com", 587, "", "DataBridge", " contacto@databridge.cl ", "", "", "");

        servicio.enviarCodigo("felipe.rojas@correo.cl", "K7M2QX", "Patrimonio Inmuebles");

        assertEquals("contacto@databridge.cl", enviado().getReplyTo());
    }

    @Test
    void en_modo_de_prueba_el_correo_de_un_deudor_de_la_demostracion_va_al_buzon_de_desvio() {
        real("equipo@gmail.com", "equipo@gmail.com").enviarCodigo("felipe.rojas@correo.cl", "K7M2QX", "Patrimonio Inmuebles");

        SimpleMailMessage mensaje = enviado();
        assertEquals("equipo@gmail.com", mensaje.getTo()[0]);
        assertEquals(1, mensaje.getTo().length, "a Felipe no le llega nada");
        assertEquals("[para felipe.rojas@correo.cl] Tu codigo de acceso", mensaje.getSubject());
        assertTrue(mensaje.getText().contains("K7M2QX"), "el correo llega entero, para poder probar con el");
    }

    @Test
    void en_modo_de_prueba_un_alias_de_la_cuenta_del_equipo_recibe_directo() {
        real("equipo@gmail.com", "equipo@gmail.com").enviarEnlace("equipo+empresatest@gmail.com",
                "http://localhost:8080/magic?token=x", 15);

        SimpleMailMessage mensaje = enviado();
        assertEquals("equipo+empresatest@gmail.com", mensaje.getTo()[0]);
        assertEquals("Tu acceso a Technical Bridge", mensaje.getSubject());
    }

    @Test
    void con_lista_y_sin_desvio_lo_que_esta_fuera_de_la_lista_no_sale() {
        real("equipo@gmail.com", "").enviarCodigo("felipe.rojas@correo.cl", "K7M2QX", "Patrimonio Inmuebles");

        verify(smtp, never()).send(any(SimpleMailMessage.class));
    }

    // ------------------------------------------------------------------
    //  El correo de prueba
    // ------------------------------------------------------------------

    @Test
    void la_prueba_dice_que_salio_a_donde_llego_y_con_que_servidor() {
        PruebaDeCorreoResponse prueba = real("equipo@gmail.com", "equipo@gmail.com").probar("camila.reyes@apofyx.cl");

        assertTrue(prueba.enviado());
        assertEquals("camila.reyes@apofyx.cl", prueba.para());
        assertEquals("equipo@gmail.com", prueba.llegaA(), "en modo de prueba llega al buzon de desvio");
        assertEquals("smtp.gmail.com:587", prueba.servidor());
        assertEquals("DataBridge <equipo@gmail.com>", prueba.remitente());
        assertTrue(prueba.modo().startsWith("modo de prueba"));
        assertNull(prueba.error());
        assertEquals("[para camila.reyes@apofyx.cl] Prueba de correo de DataBridge", enviado().getSubject());
    }

    @Test
    void si_el_servidor_rechaza_la_prueba_dice_por_que_y_nunca_muestra_la_clave() {
        MailService servicio = real("", "");
        doThrow(new MailSendException("Fallo", new IllegalStateException(
                "535-5.7.8 Username and Password not accepted\n para abcd efgh ijkl mnop")))
                .when(smtp).send(any(SimpleMailMessage.class));

        PruebaDeCorreoResponse prueba = servicio.probar("equipo@gmail.com");

        assertFalse(prueba.enviado());
        assertNull(prueba.llegaA());
        assertTrue(prueba.error().startsWith("535-5.7.8 Username and Password not accepted"), prueba.error());
        assertFalse(prueba.error().contains("abcd efgh ijkl mnop"), "la clave no sale ni aunque el servidor la repita");
        assertFalse(prueba.error().contains("\n"), "en una sola linea");
        assertFalse(prueba.toString().contains("abcd efgh ijkl mnop"));
    }

    @Test
    void sin_servidor_configurado_la_prueba_lo_dice_en_vez_de_fingir_que_salio() {
        PruebaDeCorreoResponse prueba = correo("").probar("equipo@gmail.com");

        assertFalse(prueba.enviado());
        assertTrue(prueba.error().contains("SMTP_HOST"));
        verify(smtp, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void si_el_modo_de_prueba_no_deja_salir_el_correo_la_prueba_lo_explica() {
        PruebaDeCorreoResponse prueba = real("equipo@gmail.com", "").probar("camila.reyes@apofyx.cl");

        assertFalse(prueba.enviado());
        assertTrue(prueba.error().contains("CORREO_SOLO_A"));
        verify(smtp, never()).send(any(SimpleMailMessage.class));
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
