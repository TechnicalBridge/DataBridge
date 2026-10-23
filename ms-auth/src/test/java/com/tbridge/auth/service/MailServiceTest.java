package com.tbridge.auth.service;

import com.tbridge.auth.dto.response.PruebaDeCorreoResponse;
import com.tbridge.auth.exception.CorreoNoSalio;
import jakarta.mail.Address;
import jakarta.mail.AuthenticationFailedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.SendFailedException;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.MailAuthenticationException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


@ExtendWith(MockitoExtension.class)
class MailServiceTest {

    @Mock
    private JavaMailSender smtp;

    @Mock
    private ObjectProvider<JavaMailSender> proveedor;

    /** Los correos se arman en memoria, como los arma el servidor de verdad. */
    @BeforeEach
    void armarEnMemoria() {
        lenient().when(smtp.createMimeMessage()).thenAnswer(i -> new MimeMessage(Session.getInstance(new Properties())));
    }

    private MailService correo(String host) {
        when(proveedor.getIfAvailable()).thenReturn(smtp);
        return new MailService(proveedor, "noreply@technicalbridge.local", "http://localhost:8080/", host);
    }

    @Test
    void pedir_codigo_entrega_un_solo_correo_sin_enlace_de_acceso() {
        correo("mailpit").enviarAcceso("persona@example.com", "K7M2QX", null, 15);
        Enviado carta = enviado();
        assertTrue(carta.texto().contains("K7M2QX"));
        assertTrue(carta.html().contains("K7M2QX"));
        assertFalse(carta.texto().contains("/magic"));
        assertFalse(carta.html().contains("<a "));
        assertTrue(carta.texto().contains("15 minutos"));
    }

    @Test
    void el_respaldo_entrega_codigo_y_enlace_en_el_mismo_mensaje() {
        String url = "http://localhost:8080/magic?token=token-ficticio";
        correo("mailpit").enviarAcceso("persona@example.com", "K7M2QX", url, 15);
        Enviado carta = enviado();
        assertTrue(carta.texto().contains("K7M2QX"));
        assertTrue(carta.texto().contains(url));
        assertTrue(carta.html().contains("K7M2QX"));
        assertTrue(carta.html().contains("href=\"" + url + "\""));
    }

    /** Con un servidor real: una cuenta que manda, su nombre, y el modo de prueba que se pida. */
    private MailService real(String soloA, String desviarA) {
        when(proveedor.getIfAvailable()).thenReturn(smtp);
        return new MailService(proveedor, "equipo@gmail.com", "http://localhost:8080/", "smtp.gmail.com", 587,
                "abcd efgh ijkl mnop", "DataBridge", "", soloA, desviarA, "mailpit:1025");
    }

    /** Lo que sale en un correo, leido del mensaje que recibio el servidor. */
    record Enviado(String de, List<String> para, String responderA, String asunto, String tipo, List<String> partes,
                   String texto, String html) {
    }

    private Enviado enviado() {
        ArgumentCaptor<MimeMessage> mensaje = ArgumentCaptor.forClass(MimeMessage.class);
        verify(smtp).send(mensaje.capture());
        return leer(mensaje.getValue());
    }

    static Enviado leer(MimeMessage mensaje) {
        try {
            mensaje.saveChanges();
            List<String> para = new ArrayList<>();
            for (Address direccion : mensaje.getRecipients(Message.RecipientType.TO)) {
                para.add(((InternetAddress) direccion).getAddress());
            }
            String[] responder = mensaje.getHeader("Reply-To");
            Multipart versiones = (Multipart) mensaje.getContent();
            List<String> partes = new ArrayList<>();
            String texto = null;
            String html = null;
            for (int i = 0; i < versiones.getCount(); i++) {
                var parte = versiones.getBodyPart(i);
                partes.add(parte.getContentType().split(";")[0]);
                if (parte.isMimeType("text/plain")) {
                    texto = (String) parte.getContent();
                } else if (parte.isMimeType("text/html")) {
                    html = (String) parte.getContent();
                }
            }
            return new Enviado(((InternetAddress) mensaje.getFrom()[0]).toUnicodeString(), para,
                    responder == null ? null : InternetAddress.parseHeader(responder[0], false)[0].getAddress(),
                    mensaje.getSubject(), mensaje.getContentType().split(";")[0], partes, texto, html);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------
    //  Por la API de Brevo
    // ------------------------------------------------------------------

    private static final String CLAVE_DE_BREVO = "xkeysib-clave-de-prueba-que-nunca-se-muestra";

    /** Con una clave de Brevo, sin SMTP_HOST, y el buzon de prueba que anuncia el compose. */
    private MailService porBrevo(String soloA, String desviarA) {
        when(proveedor.getIfAvailable()).thenReturn(smtp);
        return new MailService(proveedor, "equipo@gmail.com", "http://localhost:8080/", "", 587, "", "DataBridge", "",
                soloA, desviarA, "mailpit:1025", CLAVE_DE_BREVO);
    }

    @Test
    void con_brevo_hay_por_donde_mandar_aunque_no_haya_smtp_host() {
        PruebaDeCorreoResponse prueba = porBrevo("", "").probar("equipo@gmail.com");

        assertTrue(prueba.enviado());
        assertEquals("Brevo (API)", prueba.servidor());
        assertEquals("DataBridge <equipo@gmail.com>", enviado().de());
    }

    @Test
    void con_brevo_el_modo_de_prueba_sigue_desviando() {
        porBrevo("equipo@gmail.com", "equipo@gmail.com").enviarCodigo("felipe.rojas@correo.cl", "K7M2QX", "Patrimonio Inmuebles");

        Enviado mensaje = enviado();
        assertEquals("equipo@gmail.com", mensaje.para().get(0));
        assertEquals("[para felipe.rojas@correo.cl] Tu código de acceso", mensaje.asunto());
    }

    @Test
    void si_brevo_rechaza_al_destinatario_la_culpa_es_de_la_direccion() {
        MailService servicio = porBrevo("", "");
        doThrow(new MailParseException("Brevo respondio 400 (invalid_parameter): email is not valid in to"))
                .when(smtp).send(any(MimeMessage.class));

        CorreoNoSalio fallo = assertThrows(CorreoNoSalio.class,
                () -> servicio.enviarCodigo("felipe.rojas@correo.cl", "K7M2QX", "Patrimonio Inmuebles"));

        assertTrue(fallo.deLaDireccion());
    }

    @Test
    void la_clave_de_brevo_nunca_sale_aunque_venga_en_un_error() {
        MailService servicio = porBrevo("", "");
        doThrow(new MailSendException("Brevo respondio 401 (unauthorized): Key not found " + CLAVE_DE_BREVO))
                .when(smtp).send(any(MimeMessage.class));

        PruebaDeCorreoResponse prueba = servicio.probar("equipo@gmail.com");

        assertFalse(prueba.enviado());
        assertFalse(prueba.error().contains(CLAVE_DE_BREVO), prueba.error());
        assertTrue(prueba.error().contains("Key not found"));
    }

    // ------------------------------------------------------------------
    //  Cuando el correo de un codigo no sale
    // ------------------------------------------------------------------

    /**
     * Como Spring entrega un correo que el servidor rechazo: un
     * MailSendException sin causa, con lo de cada mensaje aparte.
     */
    private static MailSendException rechazado(String respuesta, boolean direccionInvalida) throws Exception {
        Address[] la = {new InternetAddress("felipe.rojas@correo.cl")};
        Address[] ninguna = {};
        SendFailedException fallo = new SendFailedException(direccionInvalida ? "Invalid Addresses" : "Send failed",
                new MessagingException(respuesta), ninguna, direccionInvalida ? ninguna : la, direccionInvalida ? la : ninguna);
        return new MailSendException(Map.of(new Object(), fallo));
    }

    @Test
    void si_el_servidor_rechaza_la_direccion_el_codigo_lo_dice_y_la_culpa_es_de_la_direccion() throws Exception {
        MailService servicio = real("", "");
        doThrow(rechazado("550 5.1.1 The email account that you tried to reach does not exist", true))
                .when(smtp).send(any(MimeMessage.class));

        CorreoNoSalio fallo = assertThrows(CorreoNoSalio.class,
                () -> servicio.enviarCodigo("felipe.rojas@correo.cl", "K7M2QX", "Patrimonio Inmuebles"));

        assertTrue(fallo.deLaDireccion());
        assertEquals("550 5.1.1 The email account that you tried to reach does not exist", fallo.getMessage(),
                "lo que dijo el servidor, no el envoltorio de Spring");
    }

    @Test
    void con_credenciales_malas_la_culpa_es_del_servidor_y_la_clave_no_aparece() {
        MailService servicio = real("", "");
        doThrow(new MailAuthenticationException(new AuthenticationFailedException(
                "535-5.7.8 Username and Password not accepted (abcd efgh ijkl mnop)")))
                .when(smtp).send(any(MimeMessage.class));

        CorreoNoSalio fallo = assertThrows(CorreoNoSalio.class,
                () -> servicio.enviarCodigo("felipe.rojas@correo.cl", "K7M2QX", "Patrimonio Inmuebles"));

        assertFalse(fallo.deLaDireccion(), "corregidas las credenciales, el mismo correo sale");
        assertTrue(fallo.getMessage().startsWith("535-5.7.8 Username and Password not accepted"), fallo.getMessage());
        assertFalse(fallo.getMessage().contains("abcd efgh ijkl mnop"));
    }

    @Test
    void un_rechazo_temporal_no_es_culpa_de_la_direccion() throws Exception {
        MailService servicio = real("", "");
        doThrow(rechazado("451 4.3.0 Mail server temporarily rejected message", false))
                .when(smtp).send(any(MimeMessage.class));

        CorreoNoSalio fallo = assertThrows(CorreoNoSalio.class,
                () -> servicio.enviarCodigo("felipe.rojas@correo.cl", "K7M2QX", "Patrimonio Inmuebles"));

        assertFalse(fallo.deLaDireccion());
    }

    @Test
    void un_servidor_que_no_responde_tampoco_es_culpa_de_la_direccion() {
        MailService servicio = real("", "");
        doThrow(new MailSendException("Mail server connection failed",
                new MessagingException("Couldn't connect to host", new java.net.SocketTimeoutException("Connect timed out"))))
                .when(smtp).send(any(MimeMessage.class));

        CorreoNoSalio fallo = assertThrows(CorreoNoSalio.class,
                () -> servicio.enviarRecordatorio("felipe.rojas@correo.cl", "K7M2QX", "Patrimonio Inmuebles",
                        LocalDate.of(2026, 10, 20)));

        assertFalse(fallo.deLaDireccion());
        assertEquals("Connect timed out", fallo.getMessage());
    }

    @Test
    void lo_que_el_modo_de_prueba_no_deja_salir_no_es_un_fallo() {
        MailService servicio = real("equipo@gmail.com", "");

        assertDoesNotThrow(() -> servicio.enviarCodigo("felipe.rojas@correo.cl", "K7M2QX", "Patrimonio Inmuebles"));
        verify(smtp, never()).send(any(MimeMessage.class));
    }

    @Test
    void el_enlace_y_la_invitacion_no_lanzan_aunque_el_servidor_falle() {
        MailService servicio = real("", "");
        doThrow(new MailAuthenticationException("535 no")).when(smtp).send(any(MimeMessage.class));

        assertDoesNotThrow(() -> servicio.enviarEnlace("camila.reyes@apofyx.cl", "http://localhost:8080/magic?token=x", 15));
        assertDoesNotThrow(() -> servicio.enviarInvitacion("camila.reyes@apofyx.cl", "Camila", "al panel",
                "http://localhost:8080/magic?token=x", 24, "http://localhost:8080/empresas"));
    }

    // ------------------------------------------------------------------
    //  Un servidor real
    // ------------------------------------------------------------------

    @Test
    void el_remitente_sale_con_su_nombre_para_que_el_buzon_diga_quien_escribe() {
        real("", "").enviarCodigo("felipe.rojas@correo.cl", "K7M2QX", "Patrimonio Inmuebles");

        assertEquals("DataBridge <equipo@gmail.com>", enviado().de());
    }

    @Test
    void con_responder_a_las_respuestas_no_van_a_la_cuenta_que_manda() {
        when(proveedor.getIfAvailable()).thenReturn(smtp);
        MailService servicio = new MailService(proveedor, "equipo@gmail.com", "http://localhost:8080/",
                "smtp.gmail.com", 587, "", "DataBridge", " contacto@databridge.cl ", "", "", "");

        servicio.enviarCodigo("felipe.rojas@correo.cl", "K7M2QX", "Patrimonio Inmuebles");

        assertEquals("contacto@databridge.cl", enviado().responderA());
    }

    @Test
    void en_modo_de_prueba_el_correo_de_un_deudor_de_la_demostracion_va_al_buzon_de_desvio() {
        real("equipo@gmail.com", "equipo@gmail.com").enviarCodigo("felipe.rojas@correo.cl", "K7M2QX", "Patrimonio Inmuebles");

        Enviado mensaje = enviado();
        assertEquals("equipo@gmail.com", mensaje.para().get(0));
        assertEquals(1, mensaje.para().size(), "a Felipe no le llega nada");
        assertEquals("[para felipe.rojas@correo.cl] Tu código de acceso", mensaje.asunto());
        assertTrue(mensaje.texto().contains("K7M2QX"), "el correo llega entero, para poder probar con el");
    }

    @Test
    void en_modo_de_prueba_un_alias_de_la_cuenta_del_equipo_recibe_directo() {
        real("equipo@gmail.com", "equipo@gmail.com").enviarEnlace("equipo+empresatest@gmail.com",
                "http://localhost:8080/magic?token=x", 15);

        Enviado mensaje = enviado();
        assertEquals("equipo+empresatest@gmail.com", mensaje.para().get(0));
        assertEquals("Tu enlace para entrar a DataBridge", mensaje.asunto());
    }

    @Test
    void con_lista_y_sin_desvio_lo_que_esta_fuera_de_la_lista_no_sale() {
        real("equipo@gmail.com", "").enviarCodigo("felipe.rojas@correo.cl", "K7M2QX", "Patrimonio Inmuebles");

        verify(smtp, never()).send(any(MimeMessage.class));
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
        assertEquals("[para camila.reyes@apofyx.cl] Prueba de correo de DataBridge", enviado().asunto());
    }

    @Test
    void si_el_servidor_rechaza_la_prueba_dice_por_que_y_nunca_muestra_la_clave() {
        MailService servicio = real("", "");
        doThrow(new MailSendException("Fallo", new IllegalStateException(
                "535-5.7.8 Username and Password not accepted\n para abcd efgh ijkl mnop")))
                .when(smtp).send(any(MimeMessage.class));

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
        verify(smtp, never()).send(any(MimeMessage.class));
    }

    @Test
    void si_el_modo_de_prueba_no_deja_salir_el_correo_la_prueba_lo_explica() {
        PruebaDeCorreoResponse prueba = real("equipo@gmail.com", "").probar("camila.reyes@apofyx.cl");

        assertFalse(prueba.enviado());
        assertTrue(prueba.error().contains("CORREO_SOLO_A"));
        verify(smtp, never()).send(any(MimeMessage.class));
    }

    // ------------------------------------------------------------------
    //  Lo que dice cada correo
    // ------------------------------------------------------------------

    @Test
    void cada_correo_sale_en_texto_y_en_html_y_el_html_va_al_final() {
        correo("mailpit").enviarCodigo("felipe.rojas@correo.cl", "K7M2QX", "Patrimonio Inmuebles");

        Enviado mensaje = enviado();
        assertEquals("multipart/alternative", mensaje.tipo());
        //  En multipart/alternative el cliente muestra la ultima que entiende:
        //  el que no muestra HTML se queda con el texto.
        assertEquals(List.of("text/plain", "text/html"), mensaje.partes());
        assertEquals("Tu código de acceso", mensaje.asunto());
    }

    @Test
    void el_correo_del_codigo_trae_el_codigo_y_al_acreedor_pero_ningun_enlace() {
        correo("mailpit").enviarCodigo("felipe.rojas@correo.cl", "K7M2QX", "Patrimonio Inmuebles");

        Enviado mensaje = enviado();
        for (String version : List.of(mensaje.texto(), mensaje.html())) {
            assertTrue(version.contains("K7M2QX"), version);
            assertTrue(version.contains("Patrimonio Inmuebles"));
            //  La direccion va escrita, sin http://, como se escribe en el navegador.
            assertTrue(version.contains("localhost:8080"));
            assertFalse(version.contains("http"), "nada que se pueda cliquear hacia otro lado");
        }
        assertFalse(mensaje.html().contains("<a "), mensaje.html());
        assertFalse(mensaje.html().contains("href"));
    }

    @Test
    void el_recordatorio_dice_la_fecha_pero_no_el_monto_ni_un_enlace() {
        correo("mailpit").enviarRecordatorio("felipe.rojas@correo.cl", "P4R8TW", "Patrimonio Inmuebles",
                LocalDate.of(2026, 10, 20));

        Enviado mensaje = enviado();
        for (String version : List.of(mensaje.texto(), mensaje.html())) {
            assertTrue(version.contains("martes 20 de octubre"), version);
            assertTrue(version.contains("P4R8TW"));
            assertTrue(version.contains("Patrimonio Inmuebles"));
            //  Un correo que llega a quien no es no dice cuanto debe nadie.
            assertFalse(version.contains("$"));
            assertFalse(version.contains("http"));
        }
        assertFalse(mensaje.html().contains("href"));
    }

    @Test
    void sin_acreedor_el_recordatorio_no_inventa_uno() {
        correo("mailpit").enviarRecordatorio("felipe.rojas@correo.cl", "P4R8TW", null, LocalDate.of(2026, 10, 20));

        Enviado mensaje = enviado();
        assertTrue(mensaje.texto().contains("vence una cuota de tu convenio.\n"), mensaje.texto());
        assertTrue(mensaje.html().contains("vence una cuota de tu convenio."), mensaje.html());
    }

    @Test
    void el_nombre_del_acreedor_no_puede_meter_html_en_el_correo() {
        correo("mailpit").enviarCodigo("felipe.rojas@correo.cl", "K7M2QX",
                "Inmobiliaria <a href=\"https://otro.example\">Paga aqui</a>");

        Enviado mensaje = enviado();
        assertFalse(mensaje.html().contains("<a href"), mensaje.html());
        assertTrue(mensaje.html().contains("Inmobiliaria &lt;a href=&quot;https://otro.example&quot;&gt;Paga aqui&lt;/a&gt;"),
                mensaje.html());
    }

    @Test
    void el_enlace_trae_su_boton_y_la_direccion_por_si_el_boton_no_funciona() {
        correo("mailpit").enviarEnlace("camila.reyes@apofyx.cl", "http://localhost:8080/magic?token=abc&x=1", 15);

        Enviado mensaje = enviado();
        assertEquals("Tu enlace para entrar a DataBridge", mensaje.asunto());
        assertTrue(mensaje.texto().contains("\nhttp://localhost:8080/magic?token=abc&x=1\n"), mensaje.texto());
        assertTrue(mensaje.texto().contains("15 minutos"));
        assertTrue(mensaje.html().contains("href=\"http://localhost:8080/magic?token=abc&amp;x=1\""), mensaje.html());
        assertTrue(mensaje.html().contains(">http://localhost:8080/magic?token=abc&amp;x=1</span>"),
                "y escrita, para copiarla");
    }

    @Test
    void la_invitacion_trae_el_enlace_cuanto_dura_y_por_donde_entrar_despues() {
        correo("mailpit").enviarInvitacion("ana.soto@empresatest.cl", "Ana Soto",
                "al portal de empresas, como parte de Empresa Test", "http://localhost:8080/magic?token=abc", 24,
                "http://localhost:8080/databridge/login");

        Enviado mensaje = enviado();
        String texto = mensaje.texto();
        assertTrue(texto.startsWith("Hola, Ana Soto:"), texto);
        assertTrue(texto.contains("como parte de Empresa Test"));
        assertTrue(texto.contains("http://localhost:8080/magic?token=abc"));
        assertTrue(texto.contains("24 horas"));
        //  Si el enlace vencio, la persona sabe por donde pedir otro.
        assertTrue(texto.contains("entra a localhost:8080/databridge/login y escribe este correo"));
        assertTrue(texto.contains("No hay contraseña."));
        assertTrue(mensaje.html().contains("Hola, Ana Soto:"));
        assertTrue(mensaje.html().contains("href=\"http://localhost:8080/magic?token=abc\""));
        //  En el HTML, la direccion se puede cortar antes de cada "/" sin
        //  cambiar lo que se copia.
        assertTrue(mensaje.html().contains("<wbr>localhost:8080<wbr>/databridge<wbr>/login"), mensaje.html());
    }

    @Test
    void una_invitacion_sin_nombre_saluda_igual() {
        correo("mailpit").enviarInvitacion("ana.soto@empresatest.cl", null, "al panel de administración",
                "http://localhost:8080/magic?token=abc", 1, "http://localhost:8080/admin/login");

        Enviado mensaje = enviado();
        assertTrue(mensaje.texto().startsWith("Hola:"), mensaje.texto());
        assertTrue(mensaje.texto().contains("por 1 hora:"));
        assertTrue(mensaje.texto().contains("al panel de administración."));
    }

    @Test
    void todos_los_correos_los_firma_databridge_y_no_queda_nada_de_la_plantilla() {
        MailService servicio = correo("mailpit");
        servicio.enviarCodigo("felipe.rojas@correo.cl", "K7M2QX", "Patrimonio Inmuebles");
        servicio.enviarRecordatorio("felipe.rojas@correo.cl", "P4R8TW", "Patrimonio Inmuebles", LocalDate.of(2026, 10, 20));
        servicio.enviarEnlace("camila.reyes@apofyx.cl", "http://localhost:8080/magic?token=x", 15);
        servicio.enviarInvitacion("ana.soto@empresatest.cl", "Ana Soto", "al panel de administración",
                "http://localhost:8080/magic?token=y", 24, "http://localhost:8080/admin/login");
        servicio.probar("camila.reyes@apofyx.cl");

        ArgumentCaptor<MimeMessage> enviados = ArgumentCaptor.forClass(MimeMessage.class);
        verify(smtp, times(5)).send(enviados.capture());
        for (MimeMessage crudo : enviados.getAllValues()) {
            Enviado mensaje = leer(crudo);
            for (String version : List.of(mensaje.texto(), mensaje.html())) {
                assertTrue(version.contains("DataBridge"), mensaje.asunto());
                assertFalse(version.contains("Technical Bridge"), mensaje.asunto());
                assertFalse(version.contains(" th:") || version.contains("[(") || version.contains("${"),
                        "sin restos de la plantilla: " + version);
            }
            assertTrue(mensaje.html().startsWith("<!DOCTYPE html>"), mensaje.asunto());
            assertTrue(mensaje.html().contains("<html lang=\"es\">"));
            assertTrue(mensaje.html().contains("@media (prefers-color-scheme: dark)"), "con su tema oscuro");
            assertFalse(mensaje.html().contains("<img"), "sin imagenes, que casi todos bloquean");
        }
    }

    @Test
    void un_remitente_mal_configurado_no_se_le_culpa_a_la_direccion_del_deudor() {
        when(proveedor.getIfAvailable()).thenReturn(smtp);
        MailService servicio = new MailService(proveedor, "no es un correo", "http://localhost:8080/", "smtp.gmail.com");

        CorreoNoSalio fallo = assertThrows(CorreoNoSalio.class,
                () -> servicio.enviarCodigo("felipe.rojas@correo.cl", "K7M2QX", "Patrimonio Inmuebles"));

        assertFalse(fallo.deLaDireccion(), "es la configuracion de DataBridge");
        assertTrue(fallo.getMessage().contains("MAIL_FROM"), fallo.getMessage());
        verify(smtp, never()).send(any(MimeMessage.class));
    }

    @Test
    void un_destinatario_mal_escrito_si_es_culpa_de_la_direccion() {
        MailService servicio = correo("mailpit");

        CorreoNoSalio fallo = assertThrows(CorreoNoSalio.class,
                () -> servicio.enviarCodigo("felipe.rojas@@correo", "K7M2QX", "Patrimonio Inmuebles"));

        assertTrue(fallo.deLaDireccion());
        verify(smtp, never()).send(any(MimeMessage.class));
    }

    @Test
    void sin_smtp_no_intenta_mandar_nada() {
        correo("").enviarCodigo("felipe.rojas@correo.cl", "K7M2QX", null);
        verify(smtp, never()).send(any(MimeMessage.class));
    }

    @Test
    void si_el_smtp_falla_el_acceso_no_se_cae() {
        MailService servicio = correo("mailpit");
        doThrow(new MailSendException("sin conexion")).when(smtp).send(any(MimeMessage.class));

        assertDoesNotThrow(() -> servicio.enviarEnlace("camila.reyes@apofyx.cl", "http://localhost:8080/magic?token=x", 15));
    }
}
