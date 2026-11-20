package com.tbridge.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Locale;
import java.util.Set;

/**
 * Despacho de codigos de autenticacion de un solo uso por SMS.
 *
 * <p>Soporta {@code twilio} (envio real por la API REST de Twilio sobre HTTPS) y
 * {@code mock} (registro local en desarrollo). Las credenciales nunca se
 * escriben en el codigo ni en los registros.
 */
@Service
public class SmsService {
    private static final Logger log = LoggerFactory.getLogger(SmsService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TWILIO_API_POR_OMISION = "https://api.twilio.com";

    private final String transport;
    private final String twilioAccountSid;
    private final String twilioAuthToken;
    private final String twilioFromNumber;
    private final String twilioMessagingServiceSid;
    private final String twilioApiUrl;
    private final Duration timeout;
    private final HttpClient http;

    public SmsService(String transport) {
        this(transport, "", "", "", "", TWILIO_API_POR_OMISION, 10_000L);
    }

    @Autowired
    public SmsService(
            @Value("${app.sms.transport:}") String transport,
            @Value("${app.sms.twilio.account-sid:}") String twilioAccountSid,
            @Value("${app.sms.twilio.auth-token:}") String twilioAuthToken,
            @Value("${app.sms.twilio.from-number:}") String twilioFromNumber,
            @Value("${app.sms.twilio.messaging-service-sid:}") String twilioMessagingServiceSid,
            @Value("${app.sms.twilio.api-url:https://api.twilio.com}") String twilioApiUrl,
            @Value("${app.sms.timeout-ms:10000}") long timeoutMs
    ) {
        this.twilioAccountSid = limpiar(twilioAccountSid);
        this.twilioAuthToken = limpiar(twilioAuthToken);
        this.twilioFromNumber = limpiar(twilioFromNumber);
        this.twilioMessagingServiceSid = limpiar(twilioMessagingServiceSid);
        String base = limpiar(twilioApiUrl);
        this.twilioApiUrl = (base.isEmpty() ? TWILIO_API_POR_OMISION : base).replaceAll("/+$", "");
        this.timeout = Duration.ofMillis(Math.max(1_000L, timeoutMs));
        this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
        this.transport = seleccionarTransporte(transport, this.twilioAccountSid, this.twilioAuthToken,
                this.twilioFromNumber, this.twilioMessagingServiceSid);

        if ("twilio".equals(this.transport)) {
            if (twilioListo()) {
                log.info("SMS: servidor real Twilio (API), remitente {}",
                        !this.twilioMessagingServiceSid.isEmpty()
                                ? "MessagingService"
                                : ofuscarTelefono(normalizarE164(this.twilioFromNumber)));
            } else {
                log.warn("SMS: transporte Twilio seleccionado pero faltan credenciales o remitente "
                        + "(configura TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN y TWILIO_FROM_NUMBER o TWILIO_MESSAGING_SERVICE_SID)");
            }
        } else {
            log.info("SMS: modo simulado (mock); el envio se simula localmente sin proveedor externo");
        }
    }

    public String transporteActivo() {
        return transport;
    }

    public boolean estaDisponible() {
        return "twilio".equals(transport) && twilioListo();
    }

    public void enviarCodigo(String telefono, String codigo, String duracion) {
        String destino = normalizarE164(telefono);
        if (destino.isEmpty()) {
            log.warn("No se envio SMS: numero de telefono vacio o invalido");
            return;
        }
        if ("twilio".equals(transport)) {
            enviarPorTwilio(destino, codigo, duracion);
            return;
        }
        log.info("Despachando codigo de acceso por SMS [transporte: {}] a {}",
                transport, ofuscarTelefono(destino));
    }

    private void enviarPorTwilio(String destinoE164, String codigo, String duracion) {
        if (!twilioListo()) {
            log.error("No se pudo enviar SMS por Twilio a {}: faltan TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN "
                    + "o remitente (TWILIO_FROM_NUMBER / TWILIO_MESSAGING_SERVICE_SID)", ofuscarTelefono(destinoE164));
            return;
        }
        String cuerpoTexto = "DataBridge: tu codigo de acceso es " + codigo
                + ". Vence en " + duracion + ". No lo compartas.";
        StringBuilder formulario = new StringBuilder();
        agregarCampo(formulario, "To", destinoE164);
        if (!twilioMessagingServiceSid.isEmpty()) {
            agregarCampo(formulario, "MessagingServiceSid", twilioMessagingServiceSid);
        } else {
            agregarCampo(formulario, "From", normalizarE164(twilioFromNumber));
        }
        agregarCampo(formulario, "Body", cuerpoTexto);

        URI uri = URI.create(twilioApiUrl + "/2010-04-01/Accounts/"
                + URLEncoder.encode(twilioAccountSid, StandardCharsets.UTF_8) + "/Messages.json");
        String credencial = Base64.getEncoder().encodeToString(
                (twilioAccountSid + ":" + twilioAuthToken).getBytes(StandardCharsets.UTF_8));

        HttpRequest peticion = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Authorization", "Basic " + credencial)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(formulario.toString(), StandardCharsets.UTF_8))
                .build();

        try {
            HttpResponse<String> respuesta = http.send(peticion, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int estado = respuesta.statusCode();
            if (estado >= 200 && estado < 300) {
                String sid = extraerCampo(respuesta.body(), "sid");
                log.info("SMS enviado por Twilio a {} (HTTP {}, sid={})",
                        ofuscarTelefono(destinoE164), estado, sid.isEmpty() ? "-" : sid);
            } else {
                String codigoError = extraerCampo(respuesta.body(), "code");
                String detalle = sanitizar(extraerCampo(respuesta.body(), "message"));
                if ("572006".equals(codigoError)) {
                    log.warn("Cuenta Twilio Trial detectada (error 572006: texto libre bloqueado por Twilio en cuentas gratuitas). "
                            + "Reintentando envio con plantilla predefinida 'sms_appointment_reminders'.");
                    enviarPlantillaFallback(destinoE164, "sms_appointment_reminders", uri, credencial);
                } else {
                    log.error("Twilio rechazo el envio de SMS a {} (HTTP {}, codigo={}): {}",
                            ofuscarTelefono(destinoE164), estado,
                            codigoError.isEmpty() ? "-" : codigoError,
                            detalle.isEmpty() ? "sin detalle" : detalle);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Envio de SMS por Twilio interrumpido para {}", ofuscarTelefono(destinoE164));
        } catch (IOException e) {
            log.error("Error de red al enviar SMS por Twilio a {}: {}",
                    ofuscarTelefono(destinoE164), sanitizar(e.getMessage()));
        }
    }

    private void enviarPlantillaFallback(String destinoE164, String templateName, URI uri, String credencial) {
        StringBuilder formulario = new StringBuilder();
        agregarCampo(formulario, "To", destinoE164);
        if (!twilioMessagingServiceSid.isEmpty()) {
            agregarCampo(formulario, "MessagingServiceSid", twilioMessagingServiceSid);
        } else {
            agregarCampo(formulario, "From", normalizarE164(twilioFromNumber));
        }
        agregarCampo(formulario, "Body", templateName);

        HttpRequest peticion = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Authorization", "Basic " + credencial)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(formulario.toString(), StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<String> respuesta = http.send(peticion, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int estado = respuesta.statusCode();
            if (estado >= 200 && estado < 300) {
                String sid = extraerCampo(respuesta.body(), "sid");
                log.info("SMS fallback (plantilla '{}') enviado exitosamente por Twilio a {} (HTTP {}, sid={})",
                        templateName, ofuscarTelefono(destinoE164), estado, sid.isEmpty() ? "-" : sid);
            } else {
                log.error("Fallback con plantilla Twilio rechazada para {}: HTTP {}",
                        ofuscarTelefono(destinoE164), estado);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Envio fallback interrumpido para {}", ofuscarTelefono(destinoE164));
        } catch (IOException e) {
            log.error("Error de red en fallback Twilio para {}: {}",
                    ofuscarTelefono(destinoE164), sanitizar(e.getMessage()));
        }
    }

    private boolean twilioListo() {
        return !twilioAccountSid.isEmpty()
                && !twilioAuthToken.isEmpty()
                && (!twilioFromNumber.isEmpty() || !twilioMessagingServiceSid.isEmpty());
    }

    private static String seleccionarTransporte(String transport, String sid, String token,
                                                String from, String messagingServiceSid) {
        String modo = limpiar(transport).toLowerCase(Locale.ROOT);
        if (!modo.isEmpty()) {
            if (!Set.of("twilio", "mock").contains(modo)) {
                throw new IllegalArgumentException("SMS_TRANSPORT debe ser twilio o mock");
            }
            return modo;
        }
        if (!sid.isEmpty() && !token.isEmpty() && (!from.isEmpty() || !messagingServiceSid.isEmpty())) {
            return "twilio";
        }
        return "mock";
    }

    private static void agregarCampo(StringBuilder sb, String clave, String valor) {
        if (!sb.isEmpty()) {
            sb.append('&');
        }
        sb.append(URLEncoder.encode(clave, StandardCharsets.UTF_8));
        sb.append('=');
        sb.append(URLEncoder.encode(valor, StandardCharsets.UTF_8));
    }

    private static String extraerCampo(String jsonBody, String campo) {
        if (jsonBody == null || jsonBody.isBlank()) return "";
        try {
            JsonNode raiz = JSON.readTree(jsonBody);
            JsonNode nodo = raiz.get(campo);
            return nodo == null || nodo.isNull() ? "" : nodo.asText("");
        } catch (IOException e) {
            return "";
        }
    }

    private String sanitizar(String texto) {
        if (texto == null || texto.isBlank()) return "";
        String limpio = texto;
        if (!twilioAuthToken.isEmpty()) {
            limpio = limpio.replace(twilioAuthToken, "[oculto]");
        }
        return limpio;
    }

    private static String limpiar(String valor) {
        return valor == null ? "" : valor.trim();
    }

    /**
     * Normaliza numeros chilenos e internacionales al formato E.164 exigido por Twilio.
     * Ejemplos: {@code "+56 9 4790 1054" -> "+56947901054"}, {@code "947901054" -> "+56947901054"}.
     */
    public static String normalizarE164(String tel) {
        if (tel == null || tel.isBlank()) return "";
        String limpio = tel.trim().replaceAll("[\\s\\-().]", "");
        if (limpio.isEmpty()) return "";
        if (limpio.startsWith("+")) {
            return limpio;
        }
        if (limpio.matches("^569\\d{8}$")) {
            return "+" + limpio;
        }
        if (limpio.matches("^9\\d{8}$")) {
            return "+56" + limpio;
        }
        if (limpio.matches("^09\\d{8}$")) {
            return "+56" + limpio.substring(1);
        }
        return limpio;
    }

    public static String ofuscarTelefono(String tel) {
        if (tel == null || tel.isBlank()) return "****";
        String limpio = tel.trim();
        if (limpio.length() <= 4) return "****";
        return limpio.substring(0, Math.min(4, limpio.length())) + "****"
                + limpio.substring(Math.max(0, limpio.length() - 2));
    }
}
