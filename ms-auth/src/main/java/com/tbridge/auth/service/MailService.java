package com.tbridge.auth.service;

import com.tbridge.auth.dto.response.PruebaDeCorreoResponse;
import jakarta.mail.internet.InternetAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.scheduling.annotation.Async;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Optional;

/**
 * El envio por correo.
 *
 * <p>Sirve con cualquier servidor SMTP: el buzon de prueba de Docker, una
 * cuenta de Gmail para probar, o un proveedor de correo transaccional. Pasar de
 * uno a otro es cambiar variables del {@code .env}, no codigo.
 *
 * <p>Sin SMTP configurado no falla: registra el contenido en el log, que es lo
 * que permite trabajar y demostrar el flujo sin depender de un servidor de
 * correo.
 *
 * <p>Con un servidor real, {@link ModoDePrueba} decide a quien se le escribe
 * de verdad: sin el, los correos inventados de los datos de demostracion
 * recibirian cobranzas.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);
    private static final Locale ES_CL = Locale.forLanguageTag("es-CL");

    private final JavaMailSender mailSender;
    private final String from;
    private final String remitente;
    private final String responderA;
    private final boolean smtpEnabled;
    private final String publicUrl;
    private final String servidor;
    private final String clave;
    private final ModoDePrueba modo;

    @Autowired
    public MailService(
            ObjectProvider<JavaMailSender> mailSender,
            @Value("${app.from-email}") String from,
            @Value("${app.public-url}") String publicUrl,
            @Value("${spring.mail.host:}") String host,
            @Value("${spring.mail.port:587}") int puerto,
            @Value("${spring.mail.password:}") String clave,
            @Value("${app.mail.from-name:DataBridge}") String nombre,
            @Value("${app.mail.reply-to:}") String responderA,
            @Value("${app.mail.solo-a:}") String soloA,
            @Value("${app.mail.desviar-a:}") String desviarA,
            @Value("${app.mail.buzon-de-prueba:}") String buzonDePrueba
    ) {
        this.mailSender = mailSender.getIfAvailable();
        this.from = from;
        this.remitente = conNombre(from, nombre);
        this.responderA = StringUtils.hasText(responderA) ? responderA.strip() : null;
        this.publicUrl = publicUrl.replaceAll("/$", "");
        this.smtpEnabled = this.mailSender != null && StringUtils.hasText(host);
        this.servidor = smtpEnabled ? host.strip() + ":" + puerto : null;
        this.clave = clave;
        this.modo = new ModoDePrueba(soloA, desviarA);
        decirComoQuedo(host, buzonDePrueba);
    }

    /** Sin nombre de remitente, sin responder-a y sin modo de prueba: como antes. Para las pruebas. */
    MailService(ObjectProvider<JavaMailSender> mailSender, String from, String publicUrl, String host) {
        this(mailSender, from, publicUrl, host, 587, "", "", "", "", "", "");
    }

    /**
     * Al arrancar, deja dicho a donde van los correos. Con un servidor real y
     * sin modo de prueba avisa, porque es justo el caso en que un dato de
     * demostracion termina escribiendole a alguien de verdad.
     */
    private void decirComoQuedo(String host, String buzonDePrueba) {
        if (!smtpEnabled) {
            log.info("Correo: sin servidor. Los codigos y los enlaces quedan en el log");
        } else if (StringUtils.hasText(buzonDePrueba) && buzonDePrueba.strip().startsWith(host.strip())) {
            log.info("Correo: al buzon de prueba ({}). Nada sale hacia afuera", servidor);
        } else if (modo.activo()) {
            log.info("Correo: servidor real {}, remitente {}; {}", servidor, from, modo.descripcion());
        } else {
            log.warn("Correo: servidor real {}, remitente {}, y SIN modo de prueba: se le escribe a cualquier "
                    + "direccion. Con datos de demostracion, define CORREO_SOLO_A y CORREO_DESVIAR_A", servidor, from);
        }
    }

    /** {@code DataBridge <cuenta@servidor.cl>}: con nombre, el buzon muestra quien escribe y no solo la direccion. */
    private static String conNombre(String direccion, String nombre) {
        if (!StringUtils.hasText(nombre)) {
            return direccion;
        }
        try {
            return new InternetAddress(direccion, nombre.strip(), StandardCharsets.UTF_8.name()).toString();
        } catch (UnsupportedEncodingException imposible) {
            return direccion;
        }
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

    /**
     * Manda un correo de prueba ahora, y cuenta como le fue: es la forma de
     * saber si las credenciales del {@code .env} sirven sin esperar a que un
     * deudor no reciba su codigo. Pasa por el modo de prueba, igual que
     * cualquier otro correo.
     */
    public PruebaDeCorreoResponse probar(String para) {
        if (!smtpEnabled) {
            return resultado(false, para, null, "No hay servidor de correo configurado: falta SMTP_HOST");
        }
        try {
            Optional<String> llego = entregar(para, "Prueba de correo de DataBridge", """
                    Hola,

                    Este es un correo de prueba de DataBridge: si lo estas leyendo, el
                    servidor de correo quedo bien configurado.

                    No tienes que hacer nada.

                    Technical Bridge
                    """);
            return llego.map(destino -> resultado(true, para, destino, null))
                    .orElseGet(() -> resultado(false, para, null,
                            "El modo de prueba no dejo salir este correo: " + para
                                    + " no esta en CORREO_SOLO_A y no hay CORREO_DESVIAR_A"));
        } catch (Exception e) {
            return resultado(false, para, null, motivo(e));
        }
    }

    private PruebaDeCorreoResponse resultado(boolean enviado, String para, String llegaA, String error) {
        return new PruebaDeCorreoResponse(enviado, para, llegaA, servidor, remitente, modo.descripcion(), error);
    }

    /** Lo que dijo el servidor, en una linea y sin la clave, por si algun servidor la repitiera en su error. */
    private String motivo(Exception e) {
        Throwable causa = e;
        while (causa.getCause() != null && causa.getCause() != causa) {
            causa = causa.getCause();
        }
        String texto = causa.getMessage() == null ? causa.getClass().getSimpleName() : causa.getMessage();
        texto = texto.replaceAll("\\s+", " ").strip();
        if (StringUtils.hasText(clave)) {
            texto = texto.replace(clave, "***");
        }
        return texto.length() > 300 ? texto.substring(0, 300) : texto;
    }

    private void enviar(String to, String asunto, String cuerpo) {
        if (!smtpEnabled) {
            return;
        }
        try {
            entregar(to, asunto, cuerpo);
        } catch (Exception e) {
            log.warn("No se pudo enviar SMTP a {}: {}", to, motivo(e));
        }
    }

    /**
     * Le entrega el correo al servidor, o lanza si el servidor no lo acepta.
     *
     * @return a donde salio de verdad, que en modo de prueba puede ser el buzon
     *         de desvio. Vacio si el modo de prueba no lo dejo salir.
     */
    private Optional<String> entregar(String to, String asunto, String cuerpo) {
        Optional<ModoDePrueba.Destino> destino = modo.para(to, asunto);
        if (destino.isEmpty()) {
            log.info("Modo de prueba: el correo para {} no sale (no esta en CORREO_SOLO_A y no hay CORREO_DESVIAR_A)", to);
            return Optional.empty();
        }
        SimpleMailMessage mensaje = new SimpleMailMessage();
        mensaje.setFrom(remitente);
        mensaje.setTo(destino.get().a());
        mensaje.setSubject(destino.get().asunto());
        mensaje.setText(cuerpo);
        if (responderA != null) {
            mensaje.setReplyTo(responderA);
        }
        mailSender.send(mensaje);
        if (destino.get().desviado()) {
            log.info("Modo de prueba: el correo para {} se desvio a {}", to, destino.get().a());
        }
        return Optional.of(destino.get().a());
    }
}
