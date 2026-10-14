package com.tbridge.auth.service;

import com.tbridge.auth.dto.response.PruebaDeCorreoResponse;
import com.tbridge.auth.exception.CorreoNoSalio;
import com.tbridge.auth.service.PlantillasDeCorreo.Carta;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * El envio por correo.
 *
 * <p>Sirve con cualquier servidor SMTP (el buzon de prueba de Docker, una
 * cuenta de Gmail, un proveedor de correo transaccional) o con la API de Brevo,
 * que va por HTTPS y es lo que funciona en Railway. Pasar de uno a otro es
 * cambiar variables del {@code .env}, no codigo.
 *
 * <p>Lo que dice cada correo esta en {@code resources/correos}
 * ({@link PlantillasDeCorreo}). Sale en HTML y en texto, juntos: el cliente que
 * no muestra HTML muestra el texto.
 *
 * <p>Sin SMTP configurado no falla: registra el contenido en el log, que es lo
 * que permite trabajar y demostrar el flujo sin depender de un servidor de
 * correo.
 *
 * <p>Con un servidor real, {@link ModoDePrueba} decide a quien se le escribe
 * de verdad: sin el, los correos inventados de los datos de demostracion
 * recibirian cobranzas.
 *
 * <p>El correo de un codigo y el de un recordatorio <b>dicen si no salieron</b>
 * ({@link CorreoNoSalio}): quien los pide es la cobranza, que no puede anotar
 * como hecha una gestion que nunca llego. El enlace y la invitacion salen en
 * segundo plano y solo dejan el fallo en el log.
 */
@Service
public class MailService {

    private static final Logger log = LoggerFactory.getLogger(MailService.class);
    private static final Locale ES_CL = Locale.forLanguageTag("es-CL");
    private static final PlantillasDeCorreo PLANTILLAS = new PlantillasDeCorreo();

    private final JavaMailSender mailSender;
    private final String from;
    private final String remitente;
    private final String responderA;
    private final boolean conServidor;
    /** La direccion del portal como se escribe en el navegador, sin {@code https://}. */
    private final String portal;
    private final String servidor;
    private final String clave;
    private final String claveDeBrevo;
    private final boolean porBrevo;
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
            @Value("${app.mail.buzon-de-prueba:}") String buzonDePrueba,
            @Value("${app.mail.brevo.api-key:}") String claveDeBrevo
    ) {
        this.mailSender = mailSender.getIfAvailable();
        this.from = from;
        this.remitente = conNombre(from, nombre);
        this.responderA = StringUtils.hasText(responderA) ? responderA.strip() : null;
        this.portal = publicUrl.strip().replaceFirst("^https?://", "").replaceAll("/$", "");
        this.porBrevo = this.mailSender != null && StringUtils.hasText(claveDeBrevo);
        this.conServidor = this.mailSender != null && (porBrevo || StringUtils.hasText(host));
        this.servidor = porBrevo ? "Brevo (API)" : conServidor ? host.strip() + ":" + puerto : null;
        this.clave = clave;
        this.claveDeBrevo = claveDeBrevo;
        this.modo = new ModoDePrueba(soloA, desviarA);
        decirComoQuedo(host, buzonDePrueba);
    }

    /** Por SMTP, sin Brevo. Para las pruebas. */
    MailService(ObjectProvider<JavaMailSender> mailSender, String from, String publicUrl, String host, int puerto,
                String clave, String nombre, String responderA, String soloA, String desviarA, String buzonDePrueba) {
        this(mailSender, from, publicUrl, host, puerto, clave, nombre, responderA, soloA, desviarA, buzonDePrueba, "");
    }

    /** Sin nombre de remitente, sin responder-a y sin modo de prueba: como antes. Para las pruebas. */
    MailService(ObjectProvider<JavaMailSender> mailSender, String from, String publicUrl, String host) {
        this(mailSender, from, publicUrl, host, 587, "", "", "", "", "", "", "");
    }

    /**
     * Al arrancar, deja dicho a donde van los correos. Con un servidor real y
     * sin modo de prueba avisa, porque es justo el caso en que un dato de
     * demostracion termina escribiendole a alguien de verdad.
     */
    private void decirComoQuedo(String host, String buzonDePrueba) {
        if (!conServidor) {
            log.info("Correo: sin servidor. Los codigos y los enlaces quedan en el log");
        } else if (porBrevo && modo.activo()) {
            log.info("Correo: por la API de Brevo, remitente {}; {}", from, modo.descripcion());
        } else if (porBrevo) {
            log.warn("Correo: por la API de Brevo, remitente {}, y SIN modo de prueba: se le escribe a cualquier "
                    + "direccion. Con datos de demostracion, define CORREO_SOLO_A y CORREO_DESVIAR_A", from);
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
     *
     * @throws CorreoNoSalio si el servidor no lo acepto.
     */
    public void enviarCodigo(String to, String codigo, String acreedor) {
        if (!conServidor) {
            //  Solo sin SMTP, que es desarrollo. Con SMTP el codigo NO va al
            //  log: quien leyera los logs podria entrar como cualquier deudor.
            log.info("Sin SMTP: codigo de acceso para {}: {}", to, codigo);
        }
        enviarODecirlo(to, "Tu código de acceso", "codigo", datos(
                "acreedor", acreedor == null ? "Una empresa" : acreedor,
                "portal", portal,
                "codigo", codigo));
    }

    /**
     * El recordatorio de una cuota que vence pronto, con un codigo nuevo para
     * entrar a pagarla.
     *
     * <p>Con las mismas reglas que el primer aviso: sin enlace y sin monto.
     * Dice la fecha, porque es lo que el deudor necesita para organizarse, y
     * una fecha sola no le cuenta a un tercero cuanto debe nadie.
     *
     * @throws CorreoNoSalio si el servidor no lo acepto.
     */
    public void enviarRecordatorio(String to, String codigo, String acreedor, LocalDate vence) {
        if (!conServidor) {
            log.info("Sin SMTP: recordatorio para {}, cuota del {}, codigo {}", to, vence, codigo);
        }
        enviarODecirlo(to, "Se acerca el vencimiento de tu cuota", "recordatorio", datos(
                "vence", vence.format(DateTimeFormatter.ofPattern("EEEE d 'de' MMMM", ES_CL)),
                "acreedor", acreedor,
                "portal", portal,
                "codigo", codigo));
    }

    /**
     * El camino de excepcion, para quien no logra entrar con el codigo. Sale en
     * segundo plano: si tardara solo cuando hay a quien mandarlo, el tiempo de
     * la respuesta diria quien es deudor.
     */
    @Async
    public void enviarEnlace(String to, String url, long minutos) {
        if (!conServidor) {
            log.info("Sin SMTP: enlace de acceso para {}: {}", to, url);
        }
        enviar(to, "Tu enlace para entrar a DataBridge", "enlace", datos(
                "url", url,
                "dura", cuanto(minutos, "minuto", "minutos")));
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
        if (!conServidor) {
            log.info("Sin SMTP: invitacion para {}: {}", to, url);
        }
        String direccion = entrada.strip().replaceFirst("^https?://", "");
        enviar(to, "Tu acceso a DataBridge", "invitacion", datos(
                "nombre", nombre == null ? "" : nombre.strip(),
                "aQue", aQue,
                "url", url,
                "dura", cuanto(horas, "hora", "horas"),
                "entrada", direccion,
                //  En el HTML, la direccion se puede cortar antes de cada "/":
                //  en un telefono no cabe en una linea.
                "entradaEnTrozos", List.of(direccion.split("(?=/)"))));
    }

    /** {@code 1 hora}, {@code 24 horas}. */
    private static String cuanto(long cantidad, String una, String varias) {
        return cantidad + " " + (cantidad == 1 ? una : varias);
    }

    /** Los datos de una plantilla, en pares nombre y valor. Un valor puede ser null: la plantilla decide. */
    private static Map<String, Object> datos(Object... pares) {
        Map<String, Object> datos = new HashMap<>();
        for (int i = 0; i < pares.length; i += 2) {
            datos.put((String) pares[i], pares[i + 1]);
        }
        return datos;
    }

    /**
     * Manda un correo de prueba ahora, y cuenta como le fue: es la forma de
     * saber si las credenciales del {@code .env} sirven sin esperar a que un
     * deudor no reciba su codigo. Pasa por el modo de prueba, igual que
     * cualquier otro correo.
     */
    public PruebaDeCorreoResponse probar(String para) {
        if (!conServidor) {
            return resultado(false, para, null, "No hay por donde mandar correos: falta SMTP_HOST o BREVO_API_KEY");
        }
        try {
            Optional<String> llego = entregar(para, "Prueba de correo de DataBridge", "prueba", Map.of());
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
        //  Lo que explica el fallo es lo mas hondo de la cadena: ahi esta la
        //  respuesta del servidor ("550 5.1.1 ...", "535 ... not accepted").
        Throwable causa = cadena(e).getLast();
        String texto = causa.getMessage() == null ? causa.getClass().getSimpleName() : causa.getMessage();
        texto = texto.replaceAll("\\s+", " ").strip();
        for (String secreto : new String[]{clave, claveDeBrevo}) {
            if (StringUtils.hasText(secreto)) {
                texto = texto.replace(secreto, "***");
            }
        }
        return texto.length() > 300 ? texto.substring(0, 300) : texto;
    }

    /**
     * El fallo y todo lo que trae debajo, de afuera hacia adentro. Spring
     * junta los correos rechazados en un {@link MailSendException} sin causa:
     * lo que paso con cada uno esta en sus excepciones por mensaje.
     */
    private static List<Throwable> cadena(Throwable e) {
        List<Throwable> eslabones = new ArrayList<>();
        Throwable actual = e;
        while (actual != null && !eslabones.contains(actual)) {
            eslabones.add(actual);
            if (actual.getCause() == null && actual instanceof MailSendException varios
                    && varios.getMessageExceptions().length > 0) {
                actual = varios.getMessageExceptions()[0];
            } else {
                actual = actual.getCause();
            }
        }
        return eslabones;
    }

    /**
     * Si lo que fallo es la direccion y no el servidor: la rechazo al
     * recibirla (550, 553: no existe, no acepta correo) o esta mal escrita.
     * Un rechazo temporal (450, 451, 452) no cuenta: ese correo sale despues.
     */
    static boolean esDeLaDireccion(Throwable e) {
        for (Throwable causa : cadena(e)) {
            if (causa instanceof AddressException || causa instanceof MailParseException) {
                return true;
            }
            if (causa instanceof SendFailedException rechazo
                    && rechazo.getInvalidAddresses() != null && rechazo.getInvalidAddresses().length > 0) {
                return true;
            }
        }
        return false;
    }

    /** Para un correo que nadie espera saber si salio: el fallo queda en el log. */
    private void enviar(String to, String asunto, String plantilla, Map<String, ?> datos) {
        if (!conServidor) {
            return;
        }
        try {
            entregar(to, asunto, plantilla, datos);
        } catch (Exception e) {
            log.warn("No se pudo enviar SMTP a {}: {}", to, motivo(e));
        }
    }

    /**
     * Para un correo que alguien espera saber si salio. Sin servidor no hay
     * nada que pueda fallar, y lo que el modo de prueba no deja salir tampoco
     * es un fallo: asi se pidio que fuera.
     */
    private void enviarODecirlo(String to, String asunto, String plantilla, Map<String, ?> datos) {
        if (!conServidor) {
            return;
        }
        try {
            entregar(to, asunto, plantilla, datos);
        } catch (Exception e) {
            String motivo = motivo(e);
            log.warn("No se pudo enviar SMTP a {}: {}", to, motivo);
            throw new CorreoNoSalio(motivo, esDeLaDireccion(e));
        }
    }

    /**
     * Escribe el correo con su plantilla y se lo entrega al servidor, o lanza
     * si el servidor no lo acepta.
     *
     * @return a donde salio de verdad, que en modo de prueba puede ser el buzon
     *         de desvio. Vacio si el modo de prueba no lo dejo salir.
     */
    private Optional<String> entregar(String to, String asunto, String plantilla, Map<String, ?> datos) {
        Optional<ModoDePrueba.Destino> destino = modo.para(to, asunto);
        if (destino.isEmpty()) {
            log.info("Modo de prueba: el correo para {} no sale (no esta en CORREO_SOLO_A y no hay CORREO_DESVIAR_A)", to);
            return Optional.empty();
        }
        mailSender.send(armar(destino.get(), PLANTILLAS.escribir(plantilla, datos)));
        if (destino.get().desviado()) {
            log.info("Modo de prueba: el correo para {} se desvio a {}", to, destino.get().a());
        }
        return Optional.of(destino.get().a());
    }

    /**
     * El correo en sus dos versiones ({@code multipart/alternative}): primero
     * el texto y despues el HTML, que es la que muestra quien puede.
     *
     * <p>Un remitente mal escrito es de la configuracion de DataBridge y se
     * informa sin su causa: si la llevara, {@link #esDeLaDireccion} culparia a
     * la direccion del deudor. Un destinatario mal escrito, en cambio, si es de
     * la direccion.
     */
    private MimeMessage armar(ModoDePrueba.Destino destino, Carta carta) {
        InternetAddress de;
        InternetAddress[] responder;
        try {
            de = new InternetAddress(remitente, true);
            responder = responderA == null ? null : InternetAddress.parse(responderA, true);
        } catch (AddressException e) {
            throw new MailSendException("MAIL_FROM o MAIL_REPLY_TO no es una direccion valida: " + e.getMessage());
        }
        InternetAddress para;
        try {
            para = new InternetAddress(destino.a(), true);
        } catch (AddressException e) {
            throw new MailParseException("La direccion del destinatario no es valida", e);
        }
        try {
            MimeMessage mensaje = mailSender.createMimeMessage();
            mensaje.setFrom(de);
            mensaje.setRecipient(Message.RecipientType.TO, para);
            if (responder != null) {
                mensaje.setReplyTo(responder);
            }
            mensaje.setSubject(destino.asunto(), StandardCharsets.UTF_8.name());
            MimeBodyPart texto = new MimeBodyPart();
            texto.setText(carta.texto(), StandardCharsets.UTF_8.name());
            MimeBodyPart html = new MimeBodyPart();
            html.setText(carta.html(), StandardCharsets.UTF_8.name(), "html");
            mensaje.setContent(new MimeMultipart("alternative", texto, html));
            return mensaje;
        } catch (MessagingException e) {
            throw new MailPreparationException("No se pudo armar el correo", e);
        }
    }
}
