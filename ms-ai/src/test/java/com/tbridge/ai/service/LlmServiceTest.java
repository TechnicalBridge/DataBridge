package com.tbridge.ai.service;

import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** El LLM, contra un servidor de mentira que habla como la API de xAI. */
class LlmServiceTest {

    private static final String RESPONSES = """
            {"id":"resp_1","object":"response","created_at":1789923791,"model":"grok-4.5","status":"completed",
             "output":[{"type":"message","id":"msg_1","role":"assistant","status":"completed",
                        "content":[{"type":"output_text","text":"%s","annotations":[]}]}]}""";
    private static final String CHAT = """
            {"id":"chat_1","object":"chat.completion","created":1789923791,"model":"grok-4.5",
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
        servidor.createContext("/v1/", intercambio -> {
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
        llm = new LlmService(OpenAIOkHttpClient.builder()
                .apiKey("llave-de-prueba")
                .baseUrl("http://127.0.0.1:" + servidor.getAddress().getPort() + "/v1")
                .maxRetries(0)
                .build(), "grok-4.5");
    }

    @AfterEach
    void bajar() {
        llm.destroy();
        servidor.stop(0);
    }

    private static final List<Map<String, Object>> HISTORIA = List.of(Map.of("role", "user", "content", "¿Cuánto debo?"));

    @Test
    void sin_llave_no_hay_llm_y_responde_el_motor_local() {
        LlmService sinLlave = new LlmService(" ", "https://api.x.ai/v1", "grok-4.5");

        assertFalse(sinLlave.configurado());
        assertEquals(Optional.empty(), sinLlave.responder(HISTORIA, MotorLocalTest.DEUDAS, "neutral"));
    }

    @Test
    void responde_con_la_api_de_responses() {
        respuestas.put("/v1/responses", new Contestacion(200, RESPONSES.formatted("  Debes $1.040.000 y UF 96,25.  ")));

        assertEquals(Optional.of("Debes $1.040.000 y UF 96,25."), llm.responder(HISTORIA, MotorLocalTest.DEUDAS, "neutral"));
        assertEquals(1, pedidos.size());
        String pedido = pedidos.getFirst();
        assertTrue(pedido.contains("\"model\":\"grok-4.5\""), pedido);
        assertTrue(pedido.contains("\"role\":\"system\""), pedido);
        assertTrue(pedido.contains("\"role\":\"user\""), pedido);
        assertTrue(pedido.contains("¿Cuánto debo?"), pedido);
    }

    @Test
    void si_no_tiene_la_api_de_responses_usa_la_de_chat() {
        respuestas.put("/v1/chat/completions", new Contestacion(200, CHAT.formatted("Tienes 2 deudas vigentes.")));

        assertEquals(Optional.of("Tienes 2 deudas vigentes."), llm.responder(HISTORIA, MotorLocalTest.DEUDAS, "neutral"));
        assertEquals(List.of("/v1/responses", "/v1/chat/completions"),
                pedidos.stream().map(p -> p.substring(0, p.indexOf(' '))).toList());
    }

    @Test
    void si_el_llm_falla_responde_el_motor_local() {
        respuestas.put("/v1/responses", new Contestacion(500, "{}"));
        respuestas.put("/v1/chat/completions", new Contestacion(500, "{}"));

        assertEquals(Optional.empty(), llm.responder(HISTORIA, MotorLocalTest.DEUDAS, "neutral"));
    }

    @Test
    void una_respuesta_en_blanco_no_cuenta() {
        respuestas.put("/v1/responses", new Contestacion(200, RESPONSES.formatted("   ")));

        assertEquals(Optional.empty(), llm.responder(HISTORIA, MotorLocalTest.DEUDAS, "neutral"));
        assertEquals(1, pedidos.size());
    }

    @Test
    void las_instrucciones_llevan_el_animo_y_las_deudas_sin_mezclar_monedas() {
        String sistema = LlmService.mensajes(HISTORIA, MotorLocalTest.DEUDAS, "frustracion").getFirst().contenido();

        assertTrue(sistema.contains("Ánimo detectado en el último mensaje: frustracion."), sistema);
        assertTrue(sistema.contains("- Patrimonio Inmuebles (Arriendo oficina) | original UF 115,50 | saldo UF 96,25 "
                + "| sin interés | total hoy UF 96,25 | en convenio de pago"), sistema);
        assertTrue(sistema.contains("| original $1.040.000 | saldo $1.040.000 | sin interés | total hoy $1.040.000 "
                + "| pendiente"), sistema);
        assertTrue(LlmService.mensajes(HISTORIA, List.of(), "neutral").getFirst().contenido()
                .endsWith("El deudor no tiene deudas visibles."));
    }

    @Test
    void el_llm_sabe_de_la_tasa_la_mora_el_total_de_hoy_y_el_descuento() {
        String sistema = LlmService.mensajes(HISTORIA, List.of(MotorLocalTest.CON_TASA), "neutral").getFirst().contenido();

        assertTrue(sistema.contains("- Patrimonio Inmuebles (Arriendo mensual) | original $900.000 | saldo $900.000 "
                + "| tasa 1,5% mensual | mora $15.900 | total hoy $915.900 | descuento: Si pagas todo antes del 3 de "
                + "noviembre, te descontamos $7.950 de intereses. | pendiente"), sistema);
        assertTrue(sistema.contains("nunca el capital"), sistema);
        assertFalse(sistema.contains("sin interés)"), "ya no dice que las cuotas no tienen interés");
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
