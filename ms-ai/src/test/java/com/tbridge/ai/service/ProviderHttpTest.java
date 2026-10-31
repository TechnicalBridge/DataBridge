package com.tbridge.ai.service;

import com.sun.net.httpserver.HttpServer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.ai.client.DeudasClient;
import com.tbridge.ai.config.LlmProperties;
import com.tbridge.ai.controller.SaludController;
import com.tbridge.ai.dto.request.Pregunta;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.slf4j.LoggerFactory;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

class ProviderHttpTest {
    static final String SECRET = "SECRET-MARKER-DO-NOT-LEAK";
    static final String PROMPT = "PROMPT-SECRET-MARKER";
    static final ObjectMapper JSON = new ObjectMapper();
    final AtomicInteger status = new AtomicInteger(200);
    final AtomicReference<String> body = new AtomicReference<>(chat("Respuesta del stub"));
    final AtomicReference<String> auth = new AtomicReference<>(), path = new AtomicReference<>(), request = new AtomicReference<>();
    final AtomicInteger calls = new AtomicInteger();
    final CountDownLatch release = new CountDownLatch(1);
    volatile boolean slow;
    HttpServer server;
    ExecutorService executor;
    ch.qos.logback.classic.Logger logger;
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs;

    static String chat(String text) {
        return "{\"id\":\"chat_1\",\"object\":\"chat.completion\",\"created\":1789923791,\"model\":\"test-model\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"" + text + "\"}}]}";
    }
    @BeforeEach void start() throws Exception {
        logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(LlmService.class);
        logs = new ch.qos.logback.core.read.ListAppender<>(); logs.start(); logger.addAppender(logs);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        executor = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(executor);
        server.createContext("/", exchange -> {
            calls.incrementAndGet(); path.set(exchange.getRequestURI().getPath());
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            request.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                if (slow) release.await(3, TimeUnit.SECONDS);
                byte[] data = body.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status.get(), data.length);
                exchange.getResponseBody().write(data);
            } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            catch (java.io.IOException closed) { /* Expected after timeout. */ }
            finally { exchange.close(); }
        }); server.start();
    }
    LlmService service(String provider, Duration timeout) {
        var endpoint = new LlmProperties.Endpoint(SECRET, "http://127.0.0.1:" + server.getAddress().getPort() + "/compatible/v1/", "test-model");
        return new LlmService(new LlmProperties(provider, endpoint, endpoint, endpoint, timeout));
    }
    void safeLogs() {
        for (var event : logs.list) {
            String text = event.getFormattedMessage();
            assertFalse(text.contains(SECRET)); assertFalse(text.contains(PROMPT));
            assertFalse(text.contains("Authorization")); assertFalse(text.contains("upstream-body"));
            assertNull(event.getThrowableProxy());
        }
    }
    @AfterEach void stop() { release.countDown(); server.stop(0); executor.shutdownNow(); safeLogs(); logger.detachAppender(logs); logs.stop(); }

    @ParameterizedTest @ValueSource(strings={"groq", "gemini", "xai"})
    void selected_provider_uses_single_chat_request_and_correct_source(String provider) throws Exception {
        var llm = service(provider, Duration.ofSeconds(2));
        try {
            var debt = mock(DeudasClient.class); when(debt.deudasDelDeudor("Bearer ficticio")).thenReturn(MotorLocalTest.DEUDAS);
            var answer = new AsistenteService(debt, llm).responder(new Pregunta(PROMPT, null), "Bearer ficticio");
            assertEquals(provider, answer.source()); assertEquals("Respuesta del stub", answer.reply());
            assertEquals(1, calls.get()); assertEquals("/compatible/v1/chat/completions", path.get());
            assertEquals("Bearer " + SECRET, auth.get());
            var payload = JSON.readTree(request.get()); assertEquals("test-model", payload.path("model").asText());
            assertTrue(payload.path("messages").isArray()); assertEquals("system", payload.path("messages").get(0).path("role").asText());
            assertEquals(PROMPT, payload.path("messages").get(1).path("content").asText());
            assertFalse(request.get().contains(SECRET));
            var mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new SaludController(llm)).build();
            String health = mvc.perform(get("/api/ai/salud")).andExpect(status().isOk())
                    .andExpect(jsonPath("$.provider").value(provider)).andExpect(jsonPath("$.model").value("test-model"))
                    .andExpect(jsonPath("$.configured").value(true)).andExpect(jsonPath("$.fallbackAvailable").value(true))
                    .andReturn().getResponse().getContentAsString();
            assertFalse(health.contains(SECRET)); assertFalse(health.contains("apiKey")); assertEquals(1, calls.get());
        } finally { llm.destroy(); }
    }
    @ParameterizedTest @ValueSource(strings={"local", "groq", "gemini", "xai"})
    void missing_key_uses_local_without_external_request(String provider) {
        var endpoint = new LlmProperties.Endpoint("", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-model");
        var llm = new LlmService(new LlmProperties(provider, endpoint, endpoint, endpoint, Duration.ofSeconds(2)));
        try {
            var debt = mock(DeudasClient.class); when(debt.deudasDelDeudor("Bearer ficticio")).thenReturn(MotorLocalTest.DEUDAS);
            var answer = new AsistenteService(debt, llm).responder(new Pregunta("cuanto debo", null), "Bearer ficticio");
            assertEquals("local-nlp", answer.source()); assertFalse(llm.configurado()); assertEquals(0, calls.get());
            assertEquals(provider, new SaludController(llm).salud().provider());
        } finally { llm.destroy(); }
    }
    static Stream<Arguments> failures() {
        return Stream.of("groq", "gemini", "xai").flatMap(provider -> Stream.of(
            Arguments.of(provider, 401, "AUTH"), Arguments.of(provider, 403, "AUTH"),
            Arguments.of(provider, 429, "RATE_LIMIT"), Arguments.of(provider, 500, "SERVER_ERROR"),
            Arguments.of(provider, 200, "PROTOCOL")));
    }
    @ParameterizedTest @MethodSource("failures")
    void provider_failure_degrades_only_after_valid_debt(String provider, int code, String category) {
        status.set(code); body.set("{\"error\":{\"message\":\"upstream-body " + SECRET + " " + PROMPT + "\"}}");
        var llm = service(provider, Duration.ofSeconds(2));
        try {
            var debt = mock(DeudasClient.class); when(debt.deudasDelDeudor("Bearer ficticio")).thenReturn(MotorLocalTest.DEUDAS);
            var answer = new AsistenteService(debt, llm).responder(new Pregunta("cuanto debo", null), "Bearer ficticio");
            assertEquals("local-nlp", answer.source()); assertEquals(1, calls.get());
            assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("category=" + category)));
        } finally { llm.destroy(); }
    }
    @ParameterizedTest @ValueSource(strings={"groq", "gemini", "xai"})
    void blank_response_is_protocol_and_falls_back(String provider) {
        body.set(chat("  ")); var llm = service(provider, Duration.ofSeconds(2));
        try { assertTrue(llm.responder(List.of(), MotorLocalTest.DEUDAS, "neutral").isEmpty());
            assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("category=PROTOCOL")));
        } finally { llm.destroy(); }
    }
    @ParameterizedTest @ValueSource(strings={"groq", "gemini", "xai"})
    void timeout_has_own_category_and_no_retry(String provider) {
        slow = true; var llm = service(provider, Duration.ofMillis(150));
        try { assertTrue(llm.responder(List.of(Map.of("role","user","content",PROMPT)), MotorLocalTest.DEUDAS, "neutral").isEmpty());
            assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("category=TIMEOUT")));
            // A deadline may expire before the server receives the request; it must never retry.
            assertTrue(calls.get() <= 1);
        } finally { release.countDown(); llm.destroy(); }
    }
    @ParameterizedTest @ValueSource(strings={"groq", "gemini", "xai"})
    void invalid_json_is_protocol(String provider) {
        body.set("{"); var llm = service(provider, Duration.ofSeconds(2));
        try { assertTrue(llm.responder(List.of(), MotorLocalTest.DEUDAS, "neutral").isEmpty());
            assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("category=PROTOCOL")));
        } finally { llm.destroy(); }
    }
    @ParameterizedTest @ValueSource(strings={"groq", "gemini", "xai"})
    void network_failure_is_distinct(String provider) {
        var llm = service(provider, Duration.ofSeconds(2)); server.stop(0);
        try { assertTrue(llm.responder(List.of(), MotorLocalTest.DEUDAS, "neutral").isEmpty());
            assertTrue(logs.list.stream().anyMatch(e -> e.getFormattedMessage().contains("category=NETWORK")));
        } finally { llm.destroy(); }
    }
}
