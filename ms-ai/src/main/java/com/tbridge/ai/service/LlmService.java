package com.tbridge.ai.service;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;
import com.tbridge.ai.util.Valor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * La respuesta con un LLM, si hay uno configurado.
 *
 * <p>Le habla a xAI (Grok) con el SDK oficial de OpenAI, que sirve para
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

    /** Nulo si no hay llave: entonces responde el motor local. */
    private final OpenAIClient cliente;
    private final String modelo;

    @Autowired
    public LlmService(@Value("${app.llm.api-key:}") String apiKey,
                      @Value("${app.llm.base-url}") String baseUrl,
                      @Value("${app.llm.model}") String modelo) {
        this(apiKey.isBlank() ? null : OpenAIOkHttpClient.builder()
                .apiKey(apiKey.strip())
                .baseUrl(baseUrl.strip().replaceAll("/+$", ""))
                //  El deudor esta mirando el chat: si el LLM no contesta en un
                //  minuto, contesta el motor local.
                .timeout(Duration.ofSeconds(60))
                .build(), modelo);
    }

    /** Con otro cliente: para probarlo contra un servidor de mentira. */
    LlmService(OpenAIClient cliente, String modelo) {
        this.cliente = cliente;
        this.modelo = modelo;
    }

    public boolean configurado() {
        return cliente != null;
    }

    /** La respuesta del LLM, o vacio si no hay LLM, si falla o si no dice nada. */
    public Optional<String> responder(List<Map<String, Object>> historia, List<Map<String, Object>> deudas,
                                      String animo) {
        if (cliente == null) {
            return Optional.empty();
        }
        List<Mensaje> mensajes = mensajes(historia, deudas, animo);
        try {
            try {
                return conTexto(textoDe(cliente.responses().create(ResponseCreateParams.builder()
                        .model(modelo)
                        .inputOfResponse(mensajes.stream().map(LlmService::paraResponses).toList())
                        .build())));
            } catch (RuntimeException sinResponses) {
                //  No todas las APIs compatibles tienen la de Responses: la de
                //  chat la tienen todas.
                ChatCompletionCreateParams.Builder chat = ChatCompletionCreateParams.builder().model(modelo);
                for (Mensaje mensaje : mensajes) {
                    switch (mensaje.rol()) {
                        case "system" -> chat.addSystemMessage(mensaje.contenido());
                        case "assistant" -> chat.addAssistantMessage(mensaje.contenido());
                        default -> chat.addUserMessage(mensaje.contenido());
                    }
                }
                return conTexto(cliente.chat().completions().create(chat.build())
                        .choices().getFirst().message().content().orElse(""));
            }
        } catch (RuntimeException fallo) {
            log.warn("El LLM no respondio; contesta el motor local: {}", fallo.getMessage());
            return Optional.empty();
        }
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

    /** Todo el texto de la respuesta, como el {@code output_text} de los SDK de OpenAI. */
    static String textoDe(Response respuesta) {
        return respuesta.output().stream()
                .flatMap(salida -> salida.message().stream())
                .map(ResponseOutputMessage::content)
                .flatMap(List::stream)
                .flatMap(contenido -> contenido.outputText().stream())
                .map(ResponseOutputText::text)
                .collect(Collectors.joining());
    }

    private static Optional<String> conTexto(String texto) {
        return Optional.of(Objects.toString(texto, "").strip()).filter(t -> !t.isEmpty());
    }

    private static ResponseInputItem paraResponses(Mensaje mensaje) {
        return ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder()
                .role(EasyInputMessage.Role.of(mensaje.rol()))
                .content(mensaje.contenido())
                .build());
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
