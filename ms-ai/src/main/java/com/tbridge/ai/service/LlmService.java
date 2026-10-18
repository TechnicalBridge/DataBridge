package com.tbridge.ai.service;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.errors.RateLimitException;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;
import com.tbridge.ai.config.LlmProperties;
import com.tbridge.ai.dto.response.Salud;
import com.tbridge.ai.util.Valor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * La respuesta con un LLM, si hay alguno configurado.
 *
 * <p>Es una cascada: varios proveedores compatibles con la API de OpenAI (el
 * SDK oficial sirve para todos), probados en el orden de la configuracion. Si
 * uno falla, o agota su cuota (HTTP 429), se salta al siguiente; al que fallo
 * se le deja en enfriamiento un rato para no reintentarlo en cada mensaje. Si
 * todos fallan, no hay respuesta y el asistente responde con su motor local de
 * reglas ({@link MotorLocal}). Nunca inventa montos: el contexto son las deudas
 * tal como las entrega ms-debt.
 */
@Service
public class LlmService implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(LlmService.class);

    /** Cuantos mensajes de la conversacion viajan al LLM, contando desde el ultimo. */
    static final int HISTORIA = 12;
    private static final Set<String> ROLES = Set.of("user", "assistant");
    /** El SDK exige una llave no vacia; para quien no la pide, como Ollama. */
    private static final String SIN_LLAVE = "sin-llave";

    /** Los nombres de la cascada, en orden, tengan o no llave. */
    private final List<String> nombres;
    /** Solo los usables, en orden de la cascada. */
    private final Map<String, Cliente> clientes;
    private final Duration enfriamiento;
    /** Proveedor -> instante hasta el que se le salta. */
    private final Map<String, Instant> enfriados = new ConcurrentHashMap<>();

    @Autowired
    public LlmService(LlmProperties props) {
        this.enfriamiento = Duration.ofMinutes(props.enfriamientoMinutos());
        Map<String, Cliente> armados = new LinkedHashMap<>();
        List<String> orden = new ArrayList<>();
        for (String nombre : props.orden()) {
            LlmProperties.Proveedor cfg = props.proveedores().get(nombre);
            if (cfg == null || cfg.baseUrl() == null || cfg.baseUrl().isBlank()) {
                continue;
            }
            orden.add(nombre);
            String llave = cfg.apiKey() == null ? "" : cfg.apiKey().strip();
            if (cfg.conLlave() && llave.isBlank()) {
                log.info("El proveedor {} no tiene llave; queda sin configurar.", nombre);
                continue;
            }
            armados.put(nombre, new Cliente(nombre, cliente(llave, cfg.baseUrl(), cfg.timeout()), cfg.model()));
        }
        this.nombres = List.copyOf(orden);
        this.clientes = armados;
    }

    /** La cascada ya armada, para probarla contra un servidor de mentira. */
    LlmService(List<Cliente> clientes, Duration enfriamiento) {
        Map<String, Cliente> armados = new LinkedHashMap<>();
        for (Cliente cliente : clientes) {
            armados.put(cliente.nombre(), cliente);
        }
        this.nombres = List.copyOf(armados.keySet());
        this.clientes = armados;
        this.enfriamiento = enfriamiento;
    }

    private static OpenAIClient cliente(String llave, String baseUrl, int segundos) {
        return OpenAIOkHttpClient.builder()
                .apiKey(llave.isBlank() ? SIN_LLAVE : llave)
                .baseUrl(baseUrl.strip().replaceAll("/+$", ""))
                //  El deudor esta mirando el chat: si el proveedor no contesta a
                //  tiempo, se pasa al siguiente.
                .timeout(Duration.ofSeconds(segundos))
                .build();
    }

    public boolean configurado() {
        return !clientes.isEmpty();
    }

    /** El estado de la cascada, para la salud: quien hay y quien esta en enfriamiento. */
    public List<Salud.EstadoProveedor> estado() {
        Instant ahora = Instant.now();
        return nombres.stream().map(nombre -> {
            Instant hasta = enfriados.get(nombre);
            long restante = hasta == null ? 0 : Math.max(0, Duration.between(ahora, hasta).toSeconds());
            return new Salud.EstadoProveedor(nombre, clientes.containsKey(nombre), restante > 0, restante);
        }).toList();
    }

    /** La respuesta y quien la dio, o vacio si no hay LLM o si todos fallan. */
    public Optional<Contestacion> responder(List<Map<String, Object>> historia, List<Map<String, Object>> deudas,
                                            String animo) {
        if (clientes.isEmpty()) {
            return Optional.empty();
        }
        List<Mensaje> mensajes = mensajes(historia, deudas, animo);
        Instant ahora = Instant.now();
        for (Cliente c : clientes.values()) {
            Instant hasta = enfriados.get(c.nombre());
            if (hasta != null && ahora.isBefore(hasta)) {
                log.debug("El proveedor {} esta en enfriamiento; se salta.", c.nombre());
                continue;
            }
            try {
                Optional<String> texto = consultar(c, mensajes);
                if (texto.isPresent()) {
                    enfriados.remove(c.nombre());
                    return Optional.of(new Contestacion(texto.get(), c.nombre()));
                }
                log.debug("El proveedor {} no dijo nada; sigue el siguiente.", c.nombre());
            } catch (RateLimitException agotado) {
                enfriar(c.nombre(), "agoto su cuota");
            } catch (RuntimeException fallo) {
                enfriar(c.nombre(), "no respondio (" + fallo.getMessage() + ")");
            }
        }
        return Optional.empty();
    }

    private void enfriar(String nombre, String motivo) {
        if (enfriamiento.isZero()) {
            log.warn("El proveedor {} {}; sigue el siguiente.", nombre, motivo);
            return;
        }
        enfriados.put(nombre, Instant.now().plus(enfriamiento));
        log.warn("El proveedor {} {}; en enfriamiento {} minutos.", nombre, motivo, enfriamiento.toMinutes());
    }

    private Optional<String> consultar(Cliente c, List<Mensaje> mensajes) {
        try {
            return conTexto(textoDe(c.cliente().responses().create(ResponseCreateParams.builder()
                    .model(c.modelo())
                    .inputOfResponse(mensajes.stream().map(LlmService::paraResponses).toList())
                    .build())));
        } catch (RateLimitException agotado) {
            //  La cuota es del proveedor entero, no de la API de Responses: a la
            //  de chat le iria igual de mal. Se propaga para enfriarlo ya.
            throw agotado;
        } catch (RuntimeException sinResponses) {
            //  No todas las APIs compatibles tienen la de Responses: la de
            //  chat la tienen todas.
            ChatCompletionCreateParams.Builder chat = ChatCompletionCreateParams.builder().model(c.modelo());
            for (Mensaje mensaje : mensajes) {
                switch (mensaje.rol()) {
                    case "system" -> chat.addSystemMessage(mensaje.contenido());
                    case "assistant" -> chat.addAssistantMessage(mensaje.contenido());
                    default -> chat.addUserMessage(mensaje.contenido());
                }
            }
            return conTexto(c.cliente().chat().completions().create(chat.build())
                    .choices().getFirst().message().content().orElse(""));
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
        clientes.values().forEach(c -> c.cliente().close());
    }

    /** Un proveedor de la cascada, ya con su cliente armado. */
    record Cliente(String nombre, OpenAIClient cliente, String modelo) {
    }

    /** El texto que respondio un proveedor, y cual fue. */
    public record Contestacion(String texto, String proveedor) {
    }

    /** Un mensaje de la conversacion que viaja al LLM. */
    record Mensaje(String rol, String contenido) {
    }
}
