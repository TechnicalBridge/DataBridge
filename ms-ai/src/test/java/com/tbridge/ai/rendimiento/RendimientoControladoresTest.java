package com.tbridge.ai.rendimiento;

import com.sun.net.httpserver.HttpServer;
import com.tbridge.ai.security.JwtTestConfig;
import com.tbridge.ai.security.JwtTestTokens;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rendimiento de los controladores de ms-ai con un LLM simulado.
 *
 * <p>El proveedor apunta a un servidor HTTP en 127.0.0.1, igual que ms-debt:
 * ninguna prueba llama a Groq ni a Gemini, asi que no gasta cupo real. Se mide la
 * latencia de extremo a extremo (HTTP del cliente, controlador, reglas y
 * proveedor simulado) y se exige un p95 holgado para no fallar por una maquina
 * lenta, pero suficiente para detectar una regresion grande.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(JwtTestConfig.class)
@ActiveProfiles("test")
class RendimientoControladoresTest {

    /** Marca que jamas debe aparecer en /api/ai/salud. */
    private static final String CLAVE_FALSA = "clave-falsa-de-rendimiento-no-publicar";
    private static final String RESPUESTA_LLM = "Debes $10.000 con Patrimonio Inmuebles.";

    private static final int CALENTAMIENTO = 10;
    private static final int MUESTRAS = 40;
    private static final double P95_SALUD_MS = 1000.0;
    private static final double P95_CHAT_MS = 2000.0;

    private static final String TOKEN = JwtTestTokens.token();
    private static final AtomicInteger LLAMADAS_LLM = new AtomicInteger();

    private static final HttpServer DEUDAS = servidor(false);
    private static final HttpServer LLM = servidor(true);

    @Autowired
    private TestRestTemplate http;

    @BeforeEach
    void limpiar() {
        LLAMADAS_LLM.set(0);
    }

    @Test
    void salud_responde_rapido_y_no_expone_la_clave() {
        calentarSalud();

        List<Double> tiempos = new ArrayList<>();
        for (int i = 0; i < MUESTRAS; i++) {
            long inicio = System.nanoTime();
            ResponseEntity<String> respuesta = http.getForEntity("/api/ai/salud", String.class);
            tiempos.add(milisegundos(inicio));

            assertEquals(200, respuesta.getStatusCode().value());
            String cuerpo = respuesta.getBody();
            assertTrue(cuerpo.contains("\"provider\":\"groq\""), cuerpo);
            assertFalse(cuerpo.contains(CLAVE_FALSA), cuerpo);
            assertFalse(cuerpo.contains("apiKey"), cuerpo);
            assertFalse(cuerpo.contains("baseUrl"), cuerpo);
            assertFalse(cuerpo.contains("Authorization"), cuerpo);
        }

        double p95 = p95(tiempos);
        System.out.printf("ms-ai /api/ai/salud: p95=%.1f ms (objetivo < %.0f ms)%n", p95, P95_SALUD_MS);
        assertTrue(p95 < P95_SALUD_MS, "p95 de /api/ai/salud: " + p95 + " ms");
    }

    @Test
    void chat_con_llm_simulado_mantiene_la_latencia() {
        calentarChat();

        List<Double> tiempos = new ArrayList<>();
        for (int i = 0; i < MUESTRAS; i++) {
            long inicio = System.nanoTime();
            ResponseEntity<Map> respuesta = pedirChat();
            tiempos.add(milisegundos(inicio));

            assertEquals(200, respuesta.getStatusCode().value());
            assertEquals("groq", respuesta.getBody().get("source"));
            assertTrue(respuesta.getBody().get("reply").toString().contains("Patrimonio Inmuebles"));
        }

        assertEquals(MUESTRAS, LLAMADAS_LLM.get(), "cada pregunta debe llegar al LLM simulado");
        double p95 = p95(tiempos);
        System.out.printf("ms-ai /api/ai/chat: p95=%.1f ms (objetivo < %.0f ms)%n", p95, P95_CHAT_MS);
        assertTrue(p95 < P95_CHAT_MS, "p95 de /api/ai/chat: " + p95 + " ms");
    }

    private void calentarSalud() {
        for (int i = 0; i < CALENTAMIENTO; i++) {
            assertEquals(200, http.getForEntity("/api/ai/salud", String.class).getStatusCode().value());
        }
    }

    private void calentarChat() {
        for (int i = 0; i < CALENTAMIENTO; i++) {
            assertEquals(200, pedirChat().getStatusCode().value());
        }
        LLAMADAS_LLM.set(0);
    }

    private ResponseEntity<Map> pedirChat() {
        HttpHeaders cabeceras = new HttpHeaders();
        cabeceras.setBearerAuth(TOKEN);
        cabeceras.setContentType(MediaType.APPLICATION_JSON);
        cabeceras.set("X-User-Rut", "16482337-7");
        Map<String, Object> cuerpo = Map.of(
                "message", "cuanto debo",
                "rut", "16482337-7",
                "messages", List.of());
        return http.exchange("/api/ai/chat?rut=16482337-7", HttpMethod.POST,
                new HttpEntity<>(cuerpo, cabeceras), Map.class);
    }

    private static double milisegundos(long inicioNanos) {
        return (System.nanoTime() - inicioNanos) / 1_000_000.0;
    }

    private static double p95(List<Double> tiempos) {
        double[] ordenados = tiempos.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        int indice = (int) Math.ceil(0.95 * ordenados.length) - 1;
        return ordenados[Math.max(0, Math.min(indice, ordenados.length - 1))];
    }

    /**
     * Un servidor de mentira para ms-debt y otro para el proveedor. El de deudas
     * devuelve el contrato que espera DeudasClient; el del LLM, un chat
     * completion compatible con OpenAI.
     */
    private static HttpServer servidor(boolean proveedor) {
        try {
            HttpServer servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            servidor.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            servidor.createContext("/", intercambio -> {
                // Se lee siempre el cuerpo para que el cliente pueda reutilizar la conexion.
                intercambio.getRequestBody().readAllBytes();
                String cuerpo;
                if (proveedor) {
                    LLAMADAS_LLM.incrementAndGet();
                    cuerpo = "{\"id\":\"chat_perf\",\"object\":\"chat.completion\",\"created\":1789923791,"
                            + "\"model\":\"test\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\","
                            + "\"message\":{\"role\":\"assistant\",\"content\":\"" + RESPUESTA_LLM + "\"}}]}";
                } else {
                    cuerpo = "{\"_embedded\":{\"debts\":[{\"acreedor\":\"Patrimonio Inmuebles\","
                            + "\"concepto\":\"Arriendo mensual\",\"estado\":\"open\",\"moneda\":\"CLP\","
                            + "\"montoOriginal\":10000,\"saldo\":10000,\"totalHoy\":10000,\"interesMora\":0,"
                            + "\"deudorRut\":\"16482337-7\"}]}}";
                }
                byte[] bytes = cuerpo.getBytes(StandardCharsets.UTF_8);
                intercambio.getResponseHeaders().set("Content-Type", "application/json");
                intercambio.sendResponseHeaders(200, bytes.length);
                intercambio.getResponseBody().write(bytes);
                intercambio.close();
            });
            servidor.start();
            return servidor;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry registro) {
        registro.add("app.llm.provider", () -> "groq");
        registro.add("app.debt-url", () -> "http://127.0.0.1:" + DEUDAS.getAddress().getPort());
        registro.add("app.llm.groq.base-url", () -> "http://127.0.0.1:" + LLM.getAddress().getPort() + "/v1");
        registro.add("app.llm.groq.api-key", () -> CLAVE_FALSA);
        registro.add("app.llm.groq.model", () -> "test");
    }

    @AfterAll
    static void apagar() {
        DEUDAS.stop(0);
        LLM.stop(0);
    }
}
