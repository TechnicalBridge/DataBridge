package com.tbridge.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.scheduling.annotation.Async;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * El envio por correo.
 *
 * <p>Sin SMTP configurado no falla: registra el contenido en el log, que es lo
 * que permite trabajar y demostrar el flujo sin depender de un servidor de
 * correo.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);
    private static final Locale ES_CL = Locale.forLanguageTag("es-CL");

    private final JavaMailSender mailSender;
    private final String from;
    private final boolean smtpEnabled;
    private final String publicUrl;

    public MailService(
            ObjectProvider<JavaMailSender> mailSender,
            @Value("${app.from-email}") String from,
            @Value("${app.public-url}") String publicUrl,
            @Value("${spring.mail.host:}") String host
    ) {
        this.mailSender = mailSender.getIfAvailable();
        this.from = from;
        this.publicUrl = publicUrl.replaceAll("/$", "");
        this.smtpEnabled = this.mailSender != null && StringUtils.hasText(host);
    }

    /**
     * El correo con el codigo.
     *
     * <p>Fijate en lo que NO lleva: ningun enlace. El deudor entra escribiendo
     * la direccion, y por eso el mensaje no le sirve a un phisher para
     * llevarlo a otra parte. Tampoco lleva el detalle de la deuda: eso vive
     * dentro del portal, asi que un mensaje que llegue a un numero equivocado
     * no expone cuanto debe alguien ni a quien.
     */
    public void enviarCodigo(String to, String codigo, String acreedor) {
        if (!smtpEnabled) {
            //  Solo sin SMTP, que es desarrollo. Con SMTP el codigo NO va al
            //  log: quien leyera los logs podria entrar como cualquier deudor.
            log.info("Sin SMTP: codigo de acceso para {}: {}", to, codigo);
        }
        enviar(to, "Tu codigo de acceso", """
                Hola,

                %s te dejo un mensaje sobre un pago pendiente.

                Para verlo, entra a %s y escribe tu RUT y este codigo:

                    %s

                No hay ningun enlace en este correo a proposito: entra
                escribiendo la direccion tu mismo. Asi puedes verificar donde
                estas antes de poner cualquier dato.

                Technical Bridge
                """.formatted(acreedor == null ? "Una empresa" : acreedor, publicUrl, codigo));
    }

    /**
     * El recordatorio de una cuota que vence pronto, con un codigo nuevo para
     * entrar a pagarla.
     *
     * <p>Con las mismas reglas que el primer aviso: sin enlace y sin monto.
     * Dice la fecha, porque es lo que el deudor necesita para organizarse, y
     * una fecha sola no le cuenta a un tercero cuanto debe nadie.
     */
    public void enviarRecordatorio(String to, String codigo, String acreedor, LocalDate vence) {
        if (!smtpEnabled) {
            log.info("Sin SMTP: recordatorio para {}, cuota del {}, codigo {}", to, vence, codigo);
        }
        enviar(to, "Se acerca el vencimiento de tu cuota", """
                Hola,

                El %s vence una cuota de tu convenio con %s.

                Si quieres pagarla ahora, entra a %s y escribe tu RUT y este codigo:

                    %s

                Como siempre, este correo no trae ningun enlace: entra escribiendo
                la direccion tu mismo.

                Si ya la pagaste, no tienes que hacer nada.

                Technical Bridge
                """.formatted(vence.format(DateTimeFormatter.ofPattern("d 'de' MMMM", ES_CL)),
                acreedor == null ? "tu acreedor" : acreedor, publicUrl, codigo));
    }

    /**
     * El camino de excepcion, para quien no logra entrar con el codigo. Sale en
     * segundo plano: si tardara solo cuando hay a quien mandarlo, el tiempo de
     * la respuesta diria quien es deudor.
     */
    @Async
    public void enviarEnlace(String to, String url, long minutos) {
        if (!smtpEnabled) {
            log.info("Sin SMTP: enlace de acceso para {}: {}", to, url);
        }
        enviar(to, "Tu acceso a Technical Bridge", """
                Hola,

                Entra con este enlace de un solo uso, valido %d minutos:

                %s

                Si no lo pediste, ignora este correo.

                Technical Bridge
                """.formatted(minutos, url));
    }

    /**
     * La invitacion: a quien un admin de DataBridge acaba de dar de alta.
     *
     * <p>Trae un enlace de un solo uso, que dura mas que el que alguien pide
     * por su cuenta: la persona no lo estaba esperando y puede abrirlo horas
     * despues. Y dice como entrar las veces siguientes, que es por donde se
     * sigue si el enlace vence. Sale en segundo plano, para que dar de alta no
     * espere al servidor de correo.
     *
     * @param aQue    a que se le dio acceso: "al portal de empresas, como parte
     *                de Empresa Test" o "al panel de administracion".
     * @param entrada la direccion donde pide un enlace nuevo con su correo.
     */
    @Async
    public void enviarInvitacion(String to, String nombre, String aQue, String url, long horas, String entrada) {
        if (!smtpEnabled) {
            log.info("Sin SMTP: invitacion para {}: {}", to, url);
        }
        enviar(to, "Tu acceso a DataBridge", """
                Hola %s,

                DataBridge te dio acceso %s.

                Entra con este enlace de un solo uso, valido %d horas:

                %s

                Las veces siguientes, o si el enlace ya vencio, entra a %s
                y escribe este correo: te llegara un enlace nuevo. No hay
                contrasena.

                Si no esperabas este correo, ignoralo.

                Technical Bridge
                """.formatted(nombre, aQue, horas, url, entrada));
    }

    private void enviar(String to, String asunto, String cuerpo) {
        if (!smtpEnabled) {
            return;
        }
        try {
            SimpleMailMessage mensaje = new SimpleMailMessage();
            mensaje.setFrom(from);
            mensaje.setTo(to);
            mensaje.setSubject(asunto);
            mensaje.setText(cuerpo);
            mailSender.send(mensaje);
        } catch (Exception e) {
            log.warn("No se pudo enviar SMTP a {}: {}", to, e.getMessage());
        }
    }
}
