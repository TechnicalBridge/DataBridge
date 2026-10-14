package com.tbridge.auth.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.mail.internet.AddressException;
import jakarta.mail.internet.InternetAddress;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailSendException;
import org.springframework.mail.MailSender;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.web.util.HtmlUtils;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Los correos por la API de Brevo (HTTPS), en vez de SMTP.
 *
 * <p>Es lo que funciona en Railway, que bloquea el SMTP de salida salvo en su
 * plan Pro. Para el resto del servicio es un {@link MailSender} mas: el modo
 * de prueba, el correo de prueba y el manejo de lo que no sale siguen igual,
 * porque viven en {@code MailService}, encima de esto.
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
public class CorreoPorBrevo implements MailSender {

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
    public void send(SimpleMailMessage mensaje) {
        enviar(mensaje);
    }

    @Override
    public void send(SimpleMailMessage... mensajes) {
        for (SimpleMailMessage mensaje : mensajes) {
            enviar(mensaje);
        }
    }

    private void enviar(SimpleMailMessage mensaje) {
        HttpRequest pedido = HttpRequest.newBuilder(url)
                .timeout(espera)
                .header("api-key", clave)
                .header("accept", "application/json")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(cuerpo(mensaje), StandardCharsets.UTF_8))
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

    /** El JSON que pide Brevo: el remitente con su nombre, los destinatarios, el asunto y el cuerpo. */
    private String cuerpo(SimpleMailMessage mensaje) {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("sender", persona(remitente(mensaje.getFrom())));
        List<Map<String, Object>> para = new ArrayList<>();
        for (String destinatario : mensaje.getTo() == null ? new String[0] : mensaje.getTo()) {
            para.add(Map.of("email", destinatario(destinatario)));
        }
        cuerpo.put("to", para);
        cuerpo.put("subject", mensaje.getSubject());
        String texto = mensaje.getText() == null ? "" : mensaje.getText();
        cuerpo.put("textContent", texto);
        //  Brevo pide siempre una version en HTML. Mientras no haya plantillas
        //  (#64), es el mismo texto, con sus saltos de linea.
        cuerpo.put("htmlContent", "<div style=\"font-family:Arial,sans-serif;font-size:14px;white-space:pre-wrap\">"
                + HtmlUtils.htmlEscape(texto) + "</div>");
        if (mensaje.getReplyTo() != null && !mensaje.getReplyTo().isBlank()) {
            cuerpo.put("replyTo", Map.of("email", mensaje.getReplyTo().strip()));
        }
        try {
            return json.writeValueAsString(cuerpo);
        } catch (JsonProcessingException imposible) {
            throw new MailSendException("No se pudo armar el correo para Brevo", imposible);
        }
    }

    /** El remitente lo configura DataBridge: si esta mal escrito, la culpa no es de ningun deudor. */
    private static InternetAddress remitente(String de) {
        try {
            return new InternetAddress(de == null ? "" : de, true);
        } catch (AddressException e) {
            throw new MailSendException("El remitente (MAIL_FROM) no es una direccion valida", e);
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
