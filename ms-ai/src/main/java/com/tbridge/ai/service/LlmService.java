package com.tbridge.ai.service;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.tbridge.ai.util.Valor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import com.tbridge.ai.config.LlmProperties;
import com.openai.errors.OpenAIServiceException;
import com.openai.errors.OpenAIIoException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * La respuesta con un LLM, si hay uno configurado.
 *
 * <p>Usa Chat Completions de Groq, Gemini o xAI con el SDK existente, que sirve para
 * cualquier API compatible: basta con su URL. Si no hay llave, o el LLM falla,
 * no hay respuesta y el asistente responde con su motor local de reglas
 * ({@link MotorLocal}). Nunca inventa montos: el contexto son las deudas tal
 * como las entrega ms-debt.
 */
@Service
public class LlmService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    /** Cuantos mensajes de la conversacion viajan al LLM, contando desde el ultimo. */
    static final int HISTORIA = 12;
    private static final Set<String> ROLES = Set.of("user", "assistant");

    private final OpenAIClient cliente;
    private final String modelo;
    private final String proveedor;

    public enum Category { NOT_CONFIGURED, AUTH, RATE_LIMIT, TIMEOUT, SERVER_ERROR, PROTOCOL, NETWORK }

    @Autowired
    public LlmService(LlmProperties properties) {
        this(properties.resolve());
    }

    private LlmService(LlmProperties.Selected config) {
        this(config.configured() ? OpenAIOkHttpClient.builder()
                .apiKey(config.apiKey()).baseUrl(config.baseUrl()).timeout(config.timeout())
                .maxRetries(0).build() : null, config.model(), config.provider());
        log.info("llm_provider={} llm_model={} llm_configured={}", proveedor, modelo, configurado());
        if (!proveedor.equals("local") && !configurado()) log.warn("llm_provider={} category=NOT_CONFIGURED", proveedor);
    }

    /** Injectable fake HTTP client; production construction always uses normalized configuration. */
    LlmService(OpenAIClient cliente, String modelo, String proveedor) {
        this.cliente = cliente;
        this.modelo = modelo;
        this.proveedor = proveedor;
    }

    public boolean configurado() { return cliente != null; }
    public String proveedor() { return proveedor; }
    public String modelo() { return modelo; }

    public Optional<String> responder(List<Map<String, Object>> historia, List<Map<String, Object>> deudas,
                                      String animo) {
        if (cliente == null) {
            if (!proveedor.equals("local")) log.warn("llm_provider={} category=NOT_CONFIGURED", proveedor);
            return Optional.empty();
        }
        try {
            ChatCompletionCreateParams.Builder chat = ChatCompletionCreateParams.builder().model(modelo);
            for (Mensaje mensaje : mensajes(historia, deudas, animo)) {
                switch (mensaje.rol()) {
                    case "system" -> chat.addSystemMessage(mensaje.contenido());
                    case "assistant" -> chat.addAssistantMessage(mensaje.contenido());
                    default -> chat.addUserMessage(mensaje.contenido());
                }
            }
            var completion = cliente.chat().completions().create(chat.build());
            String text = completion.choices().isEmpty() ? "" : completion.choices().getFirst().message().content().orElse("");
            Optional<String> result = Optional.of(text.strip()).filter(value -> !value.isEmpty());
            if (result.isEmpty()) log.warn("llm_provider={} category=PROTOCOL", proveedor);
            return result;
        } catch (RuntimeException failure) {
            log.warn("llm_provider={} category={}", proveedor, category(failure));
            return Optional.empty();
        }
    }

    static Category category(RuntimeException failure) {
        if (failure instanceof OpenAIServiceException service) {
            int status = service.statusCode();
            if (status == 401 || status == 403) return Category.AUTH;
            if (status == 429) return Category.RATE_LIMIT;
            if (status >= 500) return Category.SERVER_ERROR;
            return Category.PROTOCOL;
        }
        if (failure instanceof OpenAIIoException) {
            for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
                if (cause instanceof java.io.InterruptedIOException || cause instanceof java.net.http.HttpTimeoutException)
                    return Category.TIMEOUT;
            }
            return Category.NETWORK;
        }
        return Category.PROTOCOL;
    }

    /** Las instrucciones, con las deudas, y lo ultimo de la conversacion. */
    static List<Mensaje> mensajes(List<Map<String, Object>> historia, List<Map<String, Object>> deudas, String animo) {
        String sistema = "Eres el asistente de Technical Bridge. Ayudas a deudores en Chile a entender cuánto deben, "
                + "a quién, y cómo pagar o pagar en cuotas (de 3 a 24). El interés depende de lo que pactó el "
                + "acreedor: una deuda con tasa crece por cada día de atraso y su convenio lleva ese interés; sin "
                + "tasa, no se cobra nada extra. Lo que se paga hoy es el total hoy, no el saldo. El descuento por "
                + "pronto pago rebaja los intereses por mora, nunca el capital, y solo si se paga toda la deuda de "
                + "una vez. No inventes montos: usa solo el contexto. Nunca pidas contraseñas ni datos "
                + "bancarios, y nunca mandes enlaces para entrar. "
                + "Responde en español de Chile, breve. No ejecutes pagos: el botón Pagar abre la pasarela.\n"
                + "Ánimo detectado en el último mensaje: " + animo + ". Si es frustración, reconócela antes de "
                + "responder; si es desconfianza, explica cómo verificar que esto es legítimo.\n\n"
                + contexto(deudas);
        List<Mensaje> mensajes = new ArrayList<>();
        mensajes.add(new Mensaje("system", sistema));
        for (Map<String, Object> item : historia.subList(Math.max(0, historia.size() - HISTORIA), historia.size())) {
            Object contenido = Valor.primero(item.get("content"), item.get("message"));
            if (item.get("role") instanceof String rol && ROLES.contains(rol) && contenido != null) {
                mensajes.add(new Mensaje(rol, String.valueOf(contenido)));
            }
        }
        return mensajes;
    }

    /** Las deudas en montos legibles y sin mezclar monedas, con su tasa, su mora y su descuento. */
    static String contexto(List<Map<String, Object>> deudas) {
        if (deudas.isEmpty()) {
            return "El deudor no tiene deudas visibles.";
        }
        return "Deudas (solo lectura; pesos y UF no se suman entre si):\n" + deudas.stream()
                .map(d -> {
                    String moneda = MotorLocal.moneda(d);
                    String interes = MotorLocal.conTasa(d)
                            ? "tasa " + MotorLocal.tasa(d) + " | mora " + MotorLocal.dinero(d.get("interesMora"), moneda)
                            : "sin interés";
                    String oferta = MotorLocal.oferta(d);
                    return "- " + d.get("acreedor") + " (" + d.get("concepto") + ") | original "
                            + MotorLocal.dinero(d.get("montoOriginal"), moneda) + " | saldo "
                            + MotorLocal.dinero(d.get("saldo"), moneda) + " | " + interes + " | total hoy "
                            + MotorLocal.dinero(MotorLocal.totalHoy(d), moneda)
                            + (oferta.isEmpty() ? "" : " | descuento: " + oferta)
                            + " | " + MotorLocal.estado(d);
                })
                .collect(Collectors.joining("\n"));
    }

    @Override
    public void destroy() {
        if (cliente != null) {
            cliente.close();
        }
    }

    /** Un mensaje de la conversacion que viaja al LLM. */
    record Mensaje(String rol, String contenido) {
    }
}
