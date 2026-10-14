package com.tbridge.auth.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * Los correos por la API de Brevo (HTTPS), en vez de SMTP.
 *
 * <p>Es lo que funciona en Railway, que bloquea el SMTP de salida salvo en su
 * plan Pro. Para el resto del servicio es un {@link JavaMailSender} mas: el
 * modo de prueba, el correo de prueba y el manejo de lo que no sale siguen
 * igual, porque viven en {@code MailService}, encima de esto. De cada correo
 * toma el remitente, los destinatarios, el asunto y sus versiones en texto y
 * en HTML, y se los manda a Brevo en el JSON que pide.
 *
 * <p>Lo que no sale se distingue como en SMTP. Si Brevo rechaza al
 * destinatario, es un {@link MailParseException}, y la culpa es de la
 * direccion. Cualquier otra cosa (la clave, el remitente sin verificar, el
 * cupo del dia, Brevo que no responde) es un {@link MailSendException}: el
 * mismo correo puede salir mas tarde sin cambiar nada del deudor.
 *
 * <p>La clave va solo en la cabecera {@code api-key}: nunca en un mensaje de
 * error ni en el registro.
 */
public class CorreoPorBrevo implements JavaMailSender {

    /**
     * Brevo responde casi todo como un 400 con un codigo. Que la culpa sea del
     * destinatario se reconoce porque el parametro malo es {@code to}.
     */
    private static final Pattern DEL_DESTINATARIO = Pattern.compile("(?i)\\bin to\\b|\\bto\\[\\d*]|\\brecipient");

    /**
     * Una IP en lo que responde Brevo (la de DataBridge, cuando bloquea una
     * desconocida). El motivo termina en la cartera de la empresa, que no tiene
     * por que verla.
     */
    private static final Pattern UNA_IP = Pattern.compile(
            "\\b\\d{1,3}(?:\\.\\d{1,3}){3}\\b|\\b[0-9a-fA-F]{1,4}(?::[0-9a-fA-F]{0,4}){2,7}");

    /** Los correos se arman en memoria: esta sesion no se conecta a nada. */
    private static final Session SESION = Session.getInstance(new Properties());

    private final HttpClient http;
    private final URI url;
    private final String clave;
    private final Duration espera;
    private final ObjectMapper json;

    public CorreoPorBrevo(String url, String clave, Duration espera, ObjectMapper json) {
        this.http = HttpClient.newBuilder().connectTimeout(espera).build();
        this.url = URI.create(url);
        this.clave = clave;
        this.espera = espera;
        this.json = json;
    }

    @Override
    public MimeMessage createMimeMessage() {
        return new MimeMessage(SESION);
    }

    @Override
    public MimeMessage createMimeMessage(InputStream contenido) {
        try {
            return new MimeMessage(SESION, contenido);
        } catch (MessagingException e) {
            throw new MailParseException("No se pudo leer el correo", e);
        }
    }

    @Override
    public void send(MimeMessage... mensajes) {
        for (MimeMessage mensaje : mensajes) {
            enviar(cuerpo(mensaje));
        }
    }

    /** Un correo de solo texto, como los arma Spring en cualquier parte. */
    @Override
    public void send(SimpleMailMessage... mensajes) {
        for (SimpleMailMessage mensaje : mensajes) {
            List<String> para = new ArrayList<>();
            for (String destinatario : mensaje.getTo() == null ? new String[0] : mensaje.getTo()) {
                para.add(destinatario(destinatario));
            }
            enviar(cuerpo(remitente(mensaje.getFrom()), para, mensaje.getSubject(), mensaje.getText(), null,
                    mensaje.getReplyTo()));
        }
    }

    private void enviar(String cuerpo) {
        HttpRequest pedido = HttpRequest.newBuilder(url)
                .timeout(espera)
                .header("api-key", clave)
                .header("accept", "application/json")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(cuerpo, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> respuesta;
        try {
            respuesta = http.send(pedido, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new MailSendException("Brevo no respondio: "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new MailSendException("Se interrumpio el envio a Brevo", e);
        }
        int estado = respuesta.statusCode();
        if (estado / 100 == 2) {
            return;
        }
        String[] error = error(respuesta.body());
        String texto = "Brevo respondio " + estado + (error[0] == null ? "" : " (" + error[0] + ")") + ": "
                + UNA_IP.matcher(error[1]).replaceAll("<IP>");
        if (estado == 400 && "invalid_parameter".equals(error[0]) && DEL_DESTINATARIO.matcher(error[1]).find()) {
            throw new MailParseException(texto);
        }
        throw new MailSendException(texto);
    }

    /** El JSON de un correo armado: lo que no son direcciones esta en sus partes. */
    private String cuerpo(MimeMessage mensaje) {
        InternetAddress de = remitente(mensaje);
        List<String> para = new ArrayList<>();
        try {
            Address[] destinatarios = mensaje.getRecipients(Message.RecipientType.TO);
            for (Address destinatario : destinatarios == null ? new Address[0] : destinatarios) {
                para.add(destinatario(destinatario.toString()));
            }
        } catch (MessagingException e) {
            throw new MailParseException("La direccion del destinatario no es valida", e);
        }
        try {
            String[] versiones = new String[2];
            versiones(mensaje, versiones);
            return cuerpo(de, para, mensaje.getSubject(), versiones[0], versiones[1], responderA(mensaje));
        } catch (MessagingException | IOException e) {
            //  Sin la causa, como el remitente: el correo lo arma DataBridge.
            throw new MailSendException("No se pudo leer el correo para Brevo: " + e.getMessage());
        }
    }

    /** El JSON que pide Brevo: el remitente con su nombre, los destinatarios, el asunto y el cuerpo. */
    private String cuerpo(InternetAddress de, List<String> para, String asunto, String texto, String html,
                          String responderA) {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("sender", persona(de));
        List<Map<String, Object>> destinatarios = new ArrayList<>();
        for (String destinatario : para) {
            destinatarios.add(Map.of("email", destinatario));
        }
        cuerpo.put("to", destinatarios);
        cuerpo.put("subject", asunto);
        if (texto != null) {
            cuerpo.put("textContent", texto);
        }
        //  Brevo pide siempre la version en HTML. Un correo de solo texto la
        //  lleva igual: el mismo texto, escapado y con sus saltos de linea.
        cuerpo.put("htmlContent", html != null ? html
                : "<div style=\"font-family:Arial,sans-serif;font-size:14px;white-space:pre-wrap\">"
                        + HtmlUtils.htmlEscape(texto == null ? "" : texto) + "</div>");
        if (responderA != null && !responderA.isBlank()) {
            cuerpo.put("replyTo", Map.of("email", responderA.strip()));
        }
        try {
            return json.writeValueAsString(cuerpo);
        } catch (JsonProcessingException imposible) {
            throw new MailSendException("No se pudo armar el correo para Brevo", imposible);
        }
    }

    /**
     * El texto y el HTML del correo, donde sea que esten entre sus partes. Un
     * adjunto no cuenta, aunque sea de texto.
     */
    private static void versiones(Part parte, String[] versiones) throws MessagingException, IOException {
        Object contenido = parte.getContent();
        if (contenido instanceof Multipart partes) {
            for (int i = 0; i < partes.getCount(); i++) {
                versiones(partes.getBodyPart(i), versiones);
            }
        } else if (contenido instanceof String texto && !Part.ATTACHMENT.equalsIgnoreCase(parte.getDisposition())) {
            //  El tipo sale del contenido y no de la cabecera, que en un correo
            //  recien armado todavia no esta escrita.
            String tipo = parte.getDataHandler().getContentType().toLowerCase(Locale.ROOT);
            int cual = tipo.startsWith("text/plain") ? 0 : tipo.startsWith("text/html") ? 1 : -1;
            if (cual >= 0 && versiones[cual] == null) {
                versiones[cual] = texto;
            }
        }
    }

    /** {@code getReplyTo()} devuelve el remitente si no hay Reply-To: aqui solo cuenta el que se puso. */
    private static String responderA(MimeMessage mensaje) throws MessagingException {
        String[] cabecera = mensaje.getHeader("Reply-To");
        if (cabecera == null || cabecera.length == 0) {
            return null;
        }
        InternetAddress[] direcciones = InternetAddress.parseHeader(cabecera[0], false);
        return direcciones.length == 0 ? null : direcciones[0].getAddress();
    }

    private static InternetAddress remitente(MimeMessage mensaje) {
        try {
            Address[] de = mensaje.getFrom();
            if (de != null && de.length > 0) {
                return remitente(de[0].toString());
            }
        } catch (MessagingException e) {
            //  Se informa abajo, igual que un remitente que falta.
        }
        throw new MailSendException("El remitente (MAIL_FROM) no es una direccion valida");
    }

    /**
     * El remitente lo configura DataBridge: si esta mal escrito, la culpa no es
     * de ningun deudor. Por eso el error va sin su causa, que diria que es una
     * direccion mal escrita.
     */
    private static InternetAddress remitente(String de) {
        try {
            return new InternetAddress(de == null ? "" : de, true);
        } catch (AddressException e) {
            throw new MailSendException("El remitente (MAIL_FROM) no es una direccion valida");
        }
    }

    private static String destinatario(String para) {
        try {
            return new InternetAddress(para, true).getAddress();
        } catch (AddressException e) {
            throw new MailParseException("La direccion del destinatario no es valida", e);
        }
    }

    private static Map<String, Object> persona(InternetAddress direccion) {
        Map<String, Object> persona = new LinkedHashMap<>();
        persona.put("email", direccion.getAddress());
        if (direccion.getPersonal() != null && !direccion.getPersonal().isBlank()) {
            persona.put("name", direccion.getPersonal());
        }
        return persona;
    }

    /** El codigo y el mensaje de un error de Brevo; si no viene en su forma, lo que haya, en una linea. */
    private String[] error(String cuerpo) {
        String crudo = cuerpo == null ? "" : cuerpo.replaceAll("\\s+", " ").strip();
        try {
            JsonNode nodo = json.readTree(crudo);
            if (nodo != null && nodo.hasNonNull("message")) {
                return new String[]{nodo.hasNonNull("code") ? nodo.get("code").asText() : null,
                        nodo.get("message").asText()};
            }
        } catch (JsonProcessingException | RuntimeException noEsJson) {
            //  Lo que vino se informa tal cual, mas abajo.
        }
        return new String[]{null, crudo.isEmpty() ? "sin detalle" : (crudo.length() > 200 ? crudo.substring(0, 200) : crudo)};
    }
}
