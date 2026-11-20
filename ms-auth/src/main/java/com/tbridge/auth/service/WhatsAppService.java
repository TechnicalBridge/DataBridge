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
 * Despacho de codigos de autenticacion de un solo uso por WhatsApp.
 *
 * <p>Soporta:
 * <ul>
 *   <li>{@code evolution}: envio directo sin opt-in ni plantillas Meta usando Evolution API (Baileys/WhatsApp Web).</li>
 *   <li>{@code twilio}: envio por la API REST oficial de Twilio para WhatsApp.</li>
 *   <li>{@code mock}: registro local en consola para desarrollo y pruebas.</li>
 * </ul>
 * Las credenciales nunca se escriben en el codigo ni en los registros.
 */
@Service
public class WhatsAppService {
    private static final Logger log = LoggerFactory.getLogger(WhatsAppService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String TWILIO_API_POR_OMISION = "https://api.twilio.com";
    private static final String PREFIJO_WHATSAPP = "whatsapp:";

    private final String transport;
    private final String twilioAccountSid;
    private final String twilioAuthToken;
    private final String twilioFromNumber;
    private final String twilioMessagingServiceSid;
    private final String twilioApiUrl;
    private final String evolutionUrl;
    private final String evolutionApiKey;
    private final String evolutionInstance;
    private final Duration timeout;
    private final HttpClient http;

    public WhatsAppService(String transport) {
        this(transport, "", "", "whatsapp:+14155238886", "", TWILIO_API_POR_OMISION,
                "", "", "databridge", 10_000L);
    }

    @Autowired
    public WhatsAppService(
            @Value("${app.whatsapp.transport:}") String transport,
            @Value("${app.whatsapp.twilio.account-sid:}") String twilioAccountSid,
            @Value("${app.whatsapp.twilio.auth-token:}") String twilioAuthToken,
            @Value("${app.whatsapp.twilio.from-number:whatsapp:+14155238886}") String twilioFromNumber,
            @Value("${app.whatsapp.twilio.messaging-service-sid:}") String twilioMessagingServiceSid,
            @Value("${app.whatsapp.twilio.api-url:https://api.twilio.com}") String twilioApiUrl,
            @Value("${app.whatsapp.evolution.url:}") String evolutionUrl,
            @Value("${app.whatsapp.evolution.api-key:}") String evolutionApiKey,
            @Value("${app.whatsapp.evolution.instance:databridge}") String evolutionInstance,
            @Value("${app.whatsapp.timeout-ms:10000}") long timeoutMs
    ) {
        this.twilioAccountSid = limpiar(twilioAccountSid);
        this.twilioAuthToken = limpiar(twilioAuthToken);
        this.twilioFromNumber = limpiar(twilioFromNumber);
        this.twilioMessagingServiceSid = limpiar(twilioMessagingServiceSid);
        String baseTwilio = limpiar(twilioApiUrl);
        this.twilioApiUrl = (baseTwilio.isEmpty() ? TWILIO_API_POR_OMISION : baseTwilio).replaceAll("/+$", "");

        String baseEvo = limpiar(evolutionUrl);
        this.evolutionUrl = baseEvo.replaceAll("/+$", "");
        this.evolutionApiKey = limpiar(evolutionApiKey);
        String inst = limpiar(evolutionInstance);
        this.evolutionInstance = inst.isEmpty() ? "databridge" : inst;

        this.timeout = Duration.ofMillis(Math.max(1_000L, timeoutMs));
        this.http = HttpClient.newBuilder().connectTimeout(this.timeout).build();
        this.transport = seleccionarTransporte(transport, this.evolutionUrl, this.twilioAccountSid,
                this.twilioAuthToken, this.twilioFromNumber, this.twilioMessagingServiceSid);

        if ("evolution".equals(this.transport)) {
            log.info("WhatsApp: servidor Evolution API en {}, instancia '{}'",
                    this.evolutionUrl, this.evolutionInstance);
        } else if ("twilio".equals(this.transport)) {
            if (twilioListo()) {
                log.info("WhatsApp: servidor real Twilio (API), remitente {}",
                        !this.twilioMessagingServiceSid.isEmpty()
                                ? "MessagingService"
                                : ofuscarTelefono(this.twilioFromNumber));
            } else {
                log.warn("WhatsApp: transporte Twilio seleccionado pero faltan credenciales o remitente");
            }
        } else {
            log.info("WhatsApp: modo simulado (mock); el envio se simula localmente sin proveedor externo");
        }
    }

    public String transporteActivo() {
        return transport;
    }

    public boolean estaDisponible() {
        if ("evolution".equals(transport)) {
            return evolutionListo();
        }
        if ("twilio".equals(transport)) {
            return twilioListo();
        }
        return "mock".equals(transport);
    }

    public void enviarCodigo(String telefono, String codigo, String duracion) {
        String destino = normalizarE164(telefono);
        if (destino.isEmpty()) {
            log.warn("No se envio WhatsApp: numero de telefono vacio o invalido");
            return;
        }
        if ("evolution".equals(transport)) {
            enviarPorEvolution(destino, codigo, duracion);
            return;
        }
        if ("twilio".equals(transport)) {
            enviarPorTwilio(destino, codigo, duracion);
            return;
        }
        log.info("Despachando codigo de acceso por WhatsApp [transporte: {}] a {}",
                transport, ofuscarTelefono(destino));
    }

    private void enviarPorEvolution(String destinoE164, String codigo, String duracion) {
        if (!evolutionListo()) {
            log.error("No se pudo enviar WhatsApp por Evolution API a {}: falta EVOLUTION_URL",
                    ofuscarTelefono(destinoE164));
            return;
        }
        // Evolution API recibe numeros en formato internacional sin '+': ej. 56947901054
        String numeroPuro = destinoE164.replaceAll("^\\+", "").replaceAll("[^0-9]", "");
        String cuerpoTexto = "DataBridge: tu codigo de acceso es " + codigo
                + ". Vence en " + duracion + ". No lo compartas.";

        String peticionJson;
        try {
            peticionJson = JSON.writeValueAsString(new MensajeEvolution(numeroPuro, cuerpoTexto));
        } catch (Exception e) {
            log.error("Error al serializar mensaje para Evolution API: {}", e.getMessage());
            return;
        }

        URI uri = URI.create(evolutionUrl + "/message/sendText/" + URLEncoder.encode(evolutionInstance, StandardCharsets.UTF_8));
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(peticionJson, StandardCharsets.UTF_8));

        if (!evolutionApiKey.isEmpty()) {
            builder.header("apikey", evolutionApiKey);
        }

        try {
            HttpResponse<String> respuesta = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int estado = respuesta.statusCode();
            if (estado >= 200 && estado < 300) {
                log.info("WhatsApp enviado exitosamente por Evolution API a {} (instancia: {})",
                        ofuscarTelefono(destinoE164), evolutionInstance);
            } else if (estado == 404) {
                log.warn("Instancia '{}' de Evolution API no encontrada (HTTP 404). Intentando auto-creacion...", evolutionInstance);
                if (intentarCrearInstancia()) {
                    // Reintentar envio una vez tras crear la instancia
                    HttpResponse<String> reintento = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    if (reintento.statusCode() >= 200 && reintento.statusCode() < 300) {
                        log.info("WhatsApp enviado por Evolution API tras auto-crear instancia a {}", ofuscarTelefono(destinoE164));
                        return;
                    }
                }
                log.error("Evolution API: la instancia '{}' debe conectarse escaneando el codigo QR en {}/instance/connect/{}",
                        evolutionInstance, evolutionUrl, evolutionInstance);
            } else {
                log.error("Evolution API rechazo el envio a {} (HTTP {}): {}",
                        ofuscarTelefono(destinoE164), estado, sanitizar(respuesta.body()));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Envio de WhatsApp por Evolution API interrumpido para {}", ofuscarTelefono(destinoE164));
        } catch (IOException e) {
            log.error("Error de conexion con Evolution API ({}) al enviar WhatsApp a {}: {}",
                    evolutionUrl, ofuscarTelefono(destinoE164), sanitizar(e.getMessage()));
        }
    }

    private boolean intentarCrearInstancia() {
        try {
            URI uri = URI.create(evolutionUrl + "/instance/create");
            String payload = JSON.writeValueAsString(new CreacionInstancia(evolutionInstance, "WHATSAPP-BAILEYS", true));
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8));

            if (!evolutionApiKey.isEmpty()) {
                builder.header("apikey", evolutionApiKey);
            }

            HttpResponse<String> resp = http.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return resp.statusCode() >= 200 && resp.statusCode() < 300;
        } catch (Exception e) {
            log.warn("No se pudo auto-crear instancia en Evolution API: {}", e.getMessage());
            return false;
        }
    }

    private void enviarPorTwilio(String destinoE164, String codigo, String duracion) {
        if (!twilioListo()) {
            log.error("No se pudo enviar WhatsApp por Twilio a {}: faltan TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN "
                    + "o remitente", ofuscarTelefono(destinoE164));
            return;
        }
        String destinoWhatsApp = normalizarDestinoWhatsApp(destinoE164);
        String remitenteWhatsApp = normalizarRemitenteWhatsApp(twilioFromNumber);

        String cuerpoTexto = "DataBridge: tu codigo de acceso es " + codigo
                + ". Vence en " + duracion + ". No lo compartas.";
        StringBuilder formulario = new StringBuilder();
        agregarCampo(formulario, "To", destinoWhatsApp);
        if (!twilioMessagingServiceSid.isEmpty()) {
            agregarCampo(formulario, "MessagingServiceSid", twilioMessagingServiceSid);
        } else {
            agregarCampo(formulario, "From", remitenteWhatsApp);
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
                log.info("WhatsApp enviado por Twilio a {} (HTTP {}, sid={})",
                        ofuscarTelefono(destinoE164), estado, sid.isEmpty() ? "-" : sid);
            } else {
                String codigoError = extraerCampo(respuesta.body(), "code");
                String detalle = sanitizar(extraerCampo(respuesta.body(), "message"));
                log.error("Twilio rechazo el envio de WhatsApp a {} (HTTP {}, codigo={}): {}",
                        ofuscarTelefono(destinoE164), estado,
                        codigoError.isEmpty() ? "-" : codigoError,
                        detalle.isEmpty() ? "sin detalle" : detalle);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Envio de WhatsApp por Twilio interrumpido para {}", ofuscarTelefono(destinoE164));
        } catch (IOException e) {
            log.error("Error de red al enviar WhatsApp por Twilio a {}: {}",
                    ofuscarTelefono(destinoE164), sanitizar(e.getMessage()));
        }
    }

    private boolean evolutionListo() {
        return !evolutionUrl.isEmpty();
    }

    private boolean twilioListo() {
        return !twilioAccountSid.isEmpty()
                && !twilioAuthToken.isEmpty()
                && (!twilioFromNumber.isEmpty() || !twilioMessagingServiceSid.isEmpty());
    }

    private static String seleccionarTransporte(String transport, String evoUrl, String sid, String token,
                                                String from, String messagingServiceSid) {
        String modo = limpiar(transport).toLowerCase(Locale.ROOT);
        if (!modo.isEmpty()) {
            if (!Set.of("evolution", "twilio", "mock").contains(modo)) {
                throw new IllegalArgumentException("WHATSAPP_TRANSPORT debe ser evolution, twilio o mock");
            }
            return modo;
        }
        if (!evoUrl.isEmpty()) {
            return "evolution";
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
        if (!evolutionApiKey.isEmpty()) {
            limpio = limpio.replace(evolutionApiKey, "[oculto]");
        }
        return limpio;
    }

    private static String limpiar(String valor) {
        return valor == null ? "" : valor.trim();
    }

    public static String normalizarDestinoWhatsApp(String tel) {
        String e164 = normalizarE164(tel);
        if (e164.isEmpty()) return "";
        return e164.startsWith(PREFIJO_WHATSAPP) ? e164 : PREFIJO_WHATSAPP + e164;
    }

    public static String normalizarRemitenteWhatsApp(String remitente) {
        String limpio = limpiar(remitente);
        if (limpio.isEmpty()) return PREFIJO_WHATSAPP + "+14155238886";
        return limpio.startsWith(PREFIJO_WHATSAPP) ? limpio : PREFIJO_WHATSAPP + normalizarE164(limpio);
    }

    public static String normalizarE164(String tel) {
        if (tel == null || tel.isBlank()) return "";
        String limpio = tel.trim();
        if (limpio.startsWith(PREFIJO_WHATSAPP)) {
            limpio = limpio.substring(PREFIJO_WHATSAPP.length());
        }
        limpio = limpio.replaceAll("[\\s\\-().]", "");
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
        if (limpio.startsWith(PREFIJO_WHATSAPP)) {
            limpio = limpio.substring(PREFIJO_WHATSAPP.length());
        }
        if (limpio.length() <= 4) return "****";
        return limpio.substring(0, Math.min(4, limpio.length())) + "****"
                + limpio.substring(Math.max(0, limpio.length() - 2));
    }

    private record MensajeEvolution(String number, String text) {}
    private record CreacionInstancia(String instanceName, String integration, boolean qrcode) {}
}
