package com.tbridge.ai.service;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.sun.net.httpserver.HttpServer;
import com.tbridge.ai.config.LlmProperties;
import com.tbridge.ai.dto.response.Salud;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** La cascada de LLMs, contra un servidor de mentira que habla como la API de OpenAI. */
class LlmServiceTest {

    private static final String MODELO = "openai/gpt-oss-120b";
    private static final String RESPONSES = """
            {"id":"resp_1","object":"response","created_at":1789923791,"model":"%s","status":"completed",
             "output":[{"type":"message","id":"msg_1","role":"assistant","status":"completed",
                        "content":[{"type":"output_text","text":"%s","annotations":[]}]}]}""";
    private static final String CHAT = """
            {"id":"chat_1","object":"chat.completion","created":1789923791,"model":"%s",
             "choices":[{"index":0,"finish_reason":"stop","message":{"role":"assistant","content":"%s"}}]}""";

    private HttpServer servidor;
    private final List<String> pedidos = new ArrayList<>();
    private final Map<String, Contestacion> respuestas = new HashMap<>();
    private LlmService llm;

    /** Lo que contesta el servidor de mentira en una ruta. */
    record Contestacion(int codigo, String cuerpo) {
    }

    @BeforeEach
    void levantar() throws IOException {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        //  Un solo contexto: se enruta por la ruta pedida, que empieza con el
        //  nombre del proveedor (…/groq/v1/responses, …/gemini/v1/…).
        servidor.createContext("/", intercambio -> {
            String ruta = intercambio.getRequestURI().getPath();
            pedidos.add(ruta + " " + new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            Contestacion respuesta = respuestas.getOrDefault(ruta, new Contestacion(404, "{\"error\":{\"message\":\"no\"}}"));
            byte[] cuerpo = respuesta.cuerpo().getBytes(StandardCharsets.UTF_8);
            intercambio.getResponseHeaders().add("Content-Type", "application/json");
            intercambio.sendResponseHeaders(respuesta.codigo(), cuerpo.length);
            intercambio.getResponseBody().write(cuerpo);
            intercambio.close();
        });
        servidor.start();
    }

    @AfterEach
    void bajar() {
        if (llm != null) {
            llm.destroy();
        }
        servidor.stop(0);
    }

    private LlmService.Cliente cliente(String nombre, String modelo) {
        OpenAIClient c = OpenAIOkHttpClient.builder()
                .apiKey("llave-de-prueba")
                .baseUrl("http://127.0.0.1:" + servidor.getAddress().getPort() + "/" + nombre + "/v1")
                .maxRetries(0)
                .build();
        return new LlmService.Cliente(nombre, c, modelo);
    }

    private LlmService cascada(Duration enfriamiento, LlmService.Cliente... clientes) {
        llm = new LlmService(List.of(clientes), enfriamiento);
        return llm;
    }

    private static String texto(LlmService llm) {
        return llm.responder(HISTORIA, MotorLocalTest.DEUDAS, "neutral").map(LlmService.Contestacion::texto).orElse("");
    }

    private static final List<Map<String, Object>> HISTORIA = List.of(Map.of("role", "user", "content", "¿Cuánto debo?"));

    @Test
    void sin_proveedores_no_hay_llm_y_responde_el_motor_local() {
        llm = new LlmService(List.of(), Duration.ofMinutes(60));

        assertFalse(llm.configurado());
        assertEquals(Optional.empty(), llm.responder(HISTORIA, MotorLocalTest.DEUDAS, "neutral"));
    }

    @Test
    void sin_llave_los_proveedores_se_omiten_salvo_los_que_no_la_piden() {
        LlmProperties props = new LlmProperties(List.of("groq", "ollama"), 60, Map.of(
                "groq", new LlmProperties.Proveedor(" ", "https://ejemplo.invalid/v1", MODELO, 30, null),
                "ollama", new LlmProperties.Proveedor(null, "https://ejemplo.invalid/v1", MODELO, 30, false)));
        llm = new LlmService(props);

        assertTrue(llm.configurado());
        assertEquals(List.of("groq", "ollama"), llm.estado().stream().map(Salud.EstadoProveedor::nombre).toList());
        assertFalse(llm.estado().get(0).configurado());
        assertTrue(llm.estado().get(1).configurado());
    }

    @Test
    void responde_con_la_api_de_responses() {
        respuestas.put("/groq/v1/responses",
                new Contestacion(200, RESPONSES.formatted(MODELO, "  Debes $1.040.000 y UF 96,25.  ")));
        cascada(Duration.ofMinutes(60), cliente("groq", MODELO));

        LlmService.Contestacion respuesta = llm.responder(HISTORIA, MotorLocalTest.DEUDAS, "neutral").orElseThrow();

        assertEquals("Debes $1.040.000 y UF 96,25.", respuesta.texto());
        assertEquals("groq", respuesta.proveedor());
        assertEquals(1, pedidos.size());
        String pedido = pedidos.getFirst();
        assertTrue(pedido.contains("\"model\":\"" + MODELO + "\""), pedido);
        assertTrue(pedido.contains("\"role\":\"system\""), pedido);
        assertTrue(pedido.contains("\"role\":\"user\""), pedido);
        assertTrue(pedido.contains("¿Cuánto debo?"), pedido);
    }

    @Test
    void si_no_tiene_la_api_de_responses_usa_la_de_chat() {
        respuestas.put("/groq/v1/chat/completions", new Contestacion(200, CHAT.formatted(MODELO, "Tienes 2 deudas vigentes.")));
        cascada(Duration.ofMinutes(60), cliente("groq", MODELO));

        assertEquals("Tienes 2 deudas vigentes.", texto(llm));
        assertEquals(List.of("/groq/v1/responses", "/groq/v1/chat/completions"),
                pedidos.stream().map(p -> p.substring(0, p.indexOf(' '))).toList());
    }

    @Test
    void si_el_llm_falla_responde_el_motor_local() {
        respuestas.put("/groq/v1/responses", new Contestacion(500, "{}"));
        respuestas.put("/groq/v1/chat/completions", new Contestacion(500, "{}"));
        cascada(Duration.ofMinutes(60), cliente("groq", MODELO));

        assertEquals(Optional.empty(), llm.responder(HISTORIA, MotorLocalTest.DEUDAS, "neutral"));
    }

    @Test
    void una_respuesta_en_blanco_no_cuenta() {
        respuestas.put("/groq/v1/responses", new Contestacion(200, RESPONSES.formatted(MODELO, "   ")));
        cascada(Duration.ofMinutes(60), cliente("groq", MODELO));

        assertEquals(Optional.empty(), llm.responder(HISTORIA, MotorLocalTest.DEUDAS, "neutral"));
        assertEquals(1, pedidos.size());
    }

    @Test
    void si_el_primero_falla_pasa_al_siguiente() {
        respuestas.put("/groq/v1/responses", new Contestacion(500, "{}"));
        respuestas.put("/groq/v1/chat/completions", new Contestacion(500, "{}"));
        respuestas.put("/gemini/v1/responses", new Contestacion(200, RESPONSES.formatted(MODELO, "Dos deudas.")));
        cascada(Duration.ofMinutes(60), cliente("groq", MODELO), cliente("gemini", MODELO));

        LlmService.Contestacion respuesta = llm.responder(HISTORIA, MotorLocalTest.DEUDAS, "neutral").orElseThrow();

        assertEquals("Dos deudas.", respuesta.texto());
        assertEquals("gemini", respuesta.proveedor());
    }

    @Test
    void el_que_agota_su_cuota_queda_en_enfriamiento_y_se_salta() {
        respuestas.put("/groq/v1/responses", new Contestacion(429, "{}"));
        respuestas.put("/gemini/v1/responses", new Contestacion(200, RESPONSES.formatted(MODELO, "Dos deudas.")));
        cascada(Duration.ofMinutes(60), cliente("groq", MODELO), cliente("gemini", MODELO));

        assertEquals("gemini", llm.responder(HISTORIA, MotorLocalTest.DEUDAS, "neutral").orElseThrow().proveedor());
        //  La segunda vez, groq ya no se intenta: va directo a gemini.
        assertEquals("gemini", llm.responder(HISTORIA, MotorLocalTest.DEUDAS, "neutral").orElseThrow().proveedor());

        assertEquals(List.of("/groq/v1/responses", "/gemini/v1/responses", "/gemini/v1/responses"),
                pedidos.stream().map(p -> p.substring(0, p.indexOf(' '))).toList());
        assertTrue(llm.estado().stream().filter(e -> e.nombre().equals("groq")).findFirst().orElseThrow().enEnfriamiento());
    }

    @Test
    void con_enfriamiento_en_cero_reintenta_siempre() {
        respuestas.put("/groq/v1/responses", new Contestacion(500, "{}"));
        respuestas.put("/groq/v1/chat/completions", new Contestacion(500, "{}"));
        cascada(Duration.ZERO, cliente("groq", MODELO));

        llm.responder(HISTORIA, MotorLocalTest.DEUDAS, "neutral");
        llm.responder(HISTORIA, MotorLocalTest.DEUDAS, "neutral");

        assertEquals(4, pedidos.size());
    }

    @Test
    void las_instrucciones_llevan_el_animo_y_las_deudas_sin_mezclar_monedas() {
        String sistema = LlmService.mensajes(HISTORIA, MotorLocalTest.DEUDAS, "frustracion").getFirst().contenido();

        assertTrue(sistema.contains("Ánimo detectado en el último mensaje: frustracion."), sistema);
        assertTrue(sistema.contains("- Patrimonio Inmuebles (Arriendo oficina) | original UF 115,50 | saldo UF 96,25 "
                + "| en convenio de pago"), sistema);
        assertTrue(sistema.contains("| original $1.040.000 | saldo $1.040.000 | pendiente"), sistema);
        assertTrue(LlmService.mensajes(HISTORIA, List.of(), "neutral").getFirst().contenido()
                .endsWith("El deudor no tiene deudas visibles."));
    }

    @Test
    void viajan_los_ultimos_doce_mensajes_del_deudor_y_del_asistente() {
        List<Map<String, Object>> historia = new ArrayList<>(IntStream.rangeClosed(1, 14)
                .mapToObj(i -> Map.<String, Object>of("role", i % 2 == 1 ? "user" : "assistant", "content", "m" + i))
                .toList());
        historia.add(Map.of("role", "tool", "content", "no viaja"));
        historia.add(Map.of("role", "user", "content", ""));
        historia.add(Map.of("role", "user", "message", "m17"));

        List<LlmService.Mensaje> mensajes = LlmService.mensajes(historia, List.of(), "neutral");

        //  Los ultimos doce son m6..m14, el de la herramienta, el vacio y m17: viajan diez.
        assertEquals(List.of("m6", "m7", "m8", "m9", "m10", "m11", "m12", "m13", "m14", "m17"),
                mensajes.subList(1, mensajes.size()).stream().map(LlmService.Mensaje::contenido).toList());
        assertEquals("system", mensajes.getFirst().rol());
    }
}
