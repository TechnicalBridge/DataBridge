package com.tbridge.ai.throughput;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.assertj.core.api.SoftAssertions;

import static org.assertj.core.api.Assertions.assertThat;
import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Prueba de rendimiento de ms-ai con Gatling y con JUnit.
 *
 * <p>Mide las dos rutas del asistente:
 *
 * <ul>
 *   <li>{@code GET /api/ai/salud}: publica, responde si el servicio esta arriba
 *       y que proveedor de LLM tiene configurado.
 *   <li>{@code POST /api/ai/chat}: exige un JWT con rol DEBTOR. Cada pregunta
 *       lee la cartera en ms-debt con el mismo token y, si hay LLM configurado,
 *       espera la respuesta del proveedor.
 * </ul>
 *
 * <p>El chat solo se ejecuta si se entrega un token; sin el, la simulacion
 * corre unicamente la salud. Todo se puede ajustar por propiedad de sistema
 * ({@code -D...}) o por variable de entorno:
 *
 * <pre>
 *   baseUrl    BASE_URL     http://localhost:8085
 *   token      TOKEN        (vacio: sin chat)
 *              AI_TOKEN     alternativa a TOKEN
 *   tasaSalud  TASA_SALUD   5 usuarios por segundo
 *   tasaChat   TASA_CHAT    1 usuario por segundo
 *   rampa      RAMPA        5 segundos
 *   duracion   DURACION     20 segundos
 * </pre>
 *
 * <p>Para la simulacion de carga con Gatling, desde la raiz del repositorio:
 *
 * <pre>
 *   ./mvnw -f ms-ai/pom.xml -Prendimiento gatling:test \
 *       -Dgatling.simulationClass=com.tbridge.ai.throughput.PerformTestMsAI \
 *       -Dtoken="$TOKEN"
 * </pre>
 *
 * <p>Para las mediciones JUnit (Surefire), con el servicio arriba:
 *
 * <pre>
 *   ./mvnw -f ms-ai/pom.xml test -Dtest=PerformTestMsAI -DbaseUrl=http://localhost:8085
 * </pre>
 *
 * <p>El token tiene que ser el de un deudor y ms-ai tiene que poder validarlo
 * contra el JWKS de ms-auth ({@code AUTH_JWKS_URI}).
 */
public class PerformTestMsAI extends Simulation {

    private static final String BASE_URL = configuracion("baseUrl", "BASE_URL", "http://localhost:8085");
    private static final String TOKEN = token();

    private static final double TASA_SALUD = numero("tasaSalud", "TASA_SALUD", 5);
    private static final double TASA_CHAT = numero("tasaChat", "TASA_CHAT", 1);
    private static final int RAMPA = (int) numero("rampa", "RAMPA", 5);
    private static final int DURACION = (int) numero("duracion", "DURACION", 20);

    private static final String CUERPO_CHAT = "{\"message\":\"\u00bfCu\u00e1nto debo?\",\"messages\":[]}";

    public PerformTestMsAI() {
        //  JUnit instancia la clase para correr los @Test; Gatling tambien la
        //  instancia, pero su DSL solo esta disponible cuando manda Gatling.
        if (!ejecutandoConGatling()) {
            System.out.println("[PerformTestMsAI] Instanciada por JUnit: usa -Dtest=PerformTestMsAI. "
                    + "La carga completa se corre con gatling:test.");
            return;
        }

        HttpProtocolBuilder httpProtocol = http
                .baseUrl(BASE_URL)
                .acceptHeader("application/json")
                .contentTypeHeader("application/json")
                .userAgentHeader("Gatling-DataBridge-ms-ai");

        ScenarioBuilder salud = scenario("ms-ai - salud")
                .exec(http("GET /api/ai/salud")
                        .get("/api/ai/salud")
                        .check(status().is(200))
                        .check(jsonPath("$.service").is("ms-ai"))
                        .check(jsonPath("$.ok").is("true")))
                .pause(1);

        ScenarioBuilder chat = scenario("ms-ai - chat")
                .exec(http("POST /api/ai/chat")
                        .post("/api/ai/chat")
                        .header("Authorization", "Bearer " + TOKEN)
                        .body(StringBody(CUERPO_CHAT))
                        .asJson()
                        .check(status().is(200))
                        .check(jsonPath("$.reply").exists()))
                .pause(1);

        if (!PerfConfig.chatConProveedorLocal(BASE_URL, TOKEN)) {
            System.out.println(TOKEN.isBlank()
                    ? "[PerformTestMsAI] Sin token: solo se ejecuta GET /api/ai/salud."
                    : "[PerformTestMsAI] Proveedor distinto de local: solo se ejecuta GET /api/ai/salud.");
            System.out.println("[PerformTestMsAI] El chat solo se mide con LLM_PROVIDER=local;"
                    + " con uno real cada pregunta gasta cupo. Para incluirlo, pasa -Dtoken=<JWT de un DEBTOR>.");
            setUp(
                    salud.injectOpen(
                            rampUsersPerSec(1).to(TASA_SALUD).during(Duration.ofSeconds(RAMPA)),
                            constantUsersPerSec(TASA_SALUD).during(Duration.ofSeconds(DURACION))
                    ).protocols(httpProtocol)
            ).assertions(
                    global().failedRequests().percent().lt(1.0),
                    details("GET /api/ai/salud").responseTime().percentile(95).lt(1000)
            );
        } else {
            System.out.println("[PerformTestMsAI] Token y proveedor local: se ejecutan salud y chat.");
            setUp(
                    salud.injectOpen(
                            rampUsersPerSec(1).to(TASA_SALUD).during(Duration.ofSeconds(RAMPA)),
                            constantUsersPerSec(TASA_SALUD).during(Duration.ofSeconds(DURACION))
                    ).protocols(httpProtocol),
                    chat.injectOpen(
                            rampUsersPerSec(1).to(TASA_CHAT).during(Duration.ofSeconds(RAMPA)),
                            constantUsersPerSec(TASA_CHAT).during(Duration.ofSeconds(DURACION))
                    ).protocols(httpProtocol)
            ).assertions(
                    global().failedRequests().percent().lt(1.0),
                    details("GET /api/ai/salud").responseTime().percentile(95).lt(1000),
                    details("POST /api/ai/chat").responseTime().percentile(95).lt(15000)
            );
        }
    }

    /** Gatling instancia la simulacion desde io.gatling.*; JUnit no. */
    private static boolean ejecutandoConGatling() {
        for (StackTraceElement elemento : Thread.currentThread().getStackTrace()) {
            if (elemento.getClassName().startsWith("io.gatling.")) {
                return true;
            }
        }
        return false;
    }

    /** Propiedad de sistema, luego variable de entorno, luego el valor por omision. */
    private static String configuracion(String propiedad, String variable, String porOmision) {
        String valor = System.getProperty(propiedad);
        if (valor == null || valor.isBlank()) {
            valor = System.getenv(variable);
        }
        return valor == null || valor.isBlank() ? porOmision : valor.trim();
    }

    /**
     * El token del chat. Se acepta TOKEN o AI_TOKEN para no confundirlo con
     * otros tokens del entorno de pruebas.
     */
    private static String token() {
        String valor = System.getProperty("token");
        if (valor == null || valor.isBlank()) {
            valor = System.getenv("TOKEN");
        }
        if (valor == null || valor.isBlank()) {
            valor = System.getenv("AI_TOKEN");
        }
        return valor == null ? "" : valor.trim();
    }

    private static double numero(String propiedad, String variable, double porOmision) {
        String valor = configuracion(propiedad, variable, null);
        if (valor == null) {
            return porOmision;
        }
        try {
            return Double.parseDouble(valor);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "El valor de " + propiedad + " (" + variable + ") no es un numero: " + valor, e);
        }
    }

    // ------------------------------------------------------------------
    //  Medicion real desde JUnit/Surefire, sin depender del motor Gatling.
    //  Si el servicio no responde, la prueba se omite (assume) en vez de
    //  romper el build: la simulacion completa sigue siendo gatling:test.
    //
    //  El chat solo se mide si /api/ai/salud dice provider=local. Con un
    //  proveedor real (groq, gemini o xai) cada pregunta gasta cupo y dinero.
    // ------------------------------------------------------------------

    private static final int MEDICIONES = (int) numero("mediciones", "MEDICIONES", 20);
    private static final int CONCURRENCIA_MAX = (int) numero("concurrencia", "CONCURRENCIA", 4);
    private static final int PETICIONES_POR_NIVEL = (int) numero("peticiones", "PETICIONES", 10);
    private static final long SLA_P95_MS = (long) numero("slaP95", "SLA_P95_MS", 1000);
    private static final long SLA_MAX_MS = (long) numero("slaMax", "SLA_MAX_MS", 3000);
    private static final double MAX_ERROR_PCT = numero("maxErrorPct", "MAX_ERROR_PCT", 0.0);

    private record Lectura(int status, long ms) {
        boolean ok() {
            return status >= 200 && status < 300;
        }
    }

    private static HttpClient clienteHttp() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    private static Lectura pedir(HttpClient cliente, String ruta, String cuerpoJson) {
        HttpRequest.Builder peticion = HttpRequest.newBuilder(URI.create(BASE_URL + ruta))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json");
        if (!TOKEN.isBlank()) {
            peticion.header("Authorization", "Bearer " + TOKEN);
        }
        if (cuerpoJson == null) {
            peticion.GET();
        } else {
            peticion.header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(cuerpoJson));
        }
        long inicio = System.nanoTime();
        try {
            HttpResponse<String> respuesta = cliente.send(peticion.build(), HttpResponse.BodyHandlers.ofString());
            return new Lectura(respuesta.statusCode(), (System.nanoTime() - inicio) / 1_000_000);
        } catch (Exception e) {
            return new Lectura(-1, (System.nanoTime() - inicio) / 1_000_000);
        }
    }

    private static void exigirServicio(HttpClient cliente) {
        Lectura salud = pedir(cliente, "/api/ai/salud", null);
        Assumptions.assumeTrue(salud.status() > 0,
                () -> "Sin servicio en " + BASE_URL + ". Levanta ms-ai o pasa -DbaseUrl=...");
    }

    private static long percentil(List<Long> valores, double p) {
        if (valores.isEmpty()) {
            return 0;
        }
        List<Long> ordenados = new ArrayList<>(valores);
        Collections.sort(ordenados);
        int indice = (int) Math.ceil(p / 100.0 * ordenados.size()) - 1;
        return ordenados.get(Math.max(0, Math.min(indice, ordenados.size() - 1)));
    }

    private static void resumir(String nombre, List<Long> latencias, int errores, long totalMs) {
        long p50 = percentil(latencias, 50);
        long p95 = percentil(latencias, 95);
        long max = latencias.isEmpty() ? 0 : Collections.max(latencias);
        double rps = totalMs <= 0 ? 0 : latencias.size() * 1000.0 / totalMs;
        System.out.println("[PerformTestMsAI] " + nombre
                + ": n=" + latencias.size()
                + " errores=" + errores
                + " p50=" + p50 + "ms"
                + " p95=" + p95 + "ms"
                + " max=" + max + "ms"
                + " throughput=" + String.format("%.1f", rps) + " req/s");
    }

    private static int[] niveles(int maximo) {
        List<Integer> niveles = new ArrayList<>();
        for (int n = 1; n <= Math.max(1, maximo); n *= 2) {
            niveles.add(n);
        }
        return niveles.stream().mapToInt(Integer::intValue).toArray();
    }

    private static void validarSla(String escenario, List<Long> latencias, int errores, long duracionTotalMs) {
        assertThat(latencias)
                .as("[%s] Debe haberse registrado al menos una medicion", escenario)
                .isNotEmpty();

        long p50 = percentil(latencias, 50);
        long p95 = percentil(latencias, 95);
        long max = latencias.isEmpty() ? 0 : Collections.max(latencias);
        double errorPct = (errores * 100.0) / latencias.size();
        double rps = duracionTotalMs <= 0 ? 0 : (latencias.size() * 1000.0) / duracionTotalMs;

        SoftAssertions.assertSoftly(softly -> {
            softly.assertThat(errorPct)
                    .as("[%s] Porcentaje de errores HTTP (total: %d de %d)", escenario, errores, latencias.size())
                    .isLessThanOrEqualTo(MAX_ERROR_PCT);

            softly.assertThat(p95)
                    .as("[%s] Latencia p95 en ms (esperado <= %d ms, p50 fue %d ms)", escenario, SLA_P95_MS, p50)
                    .isLessThanOrEqualTo(SLA_P95_MS);

            softly.assertThat(max)
                    .as("[%s] Latencia maxima aceptable en ms (esperado <= %d ms)", escenario, SLA_MAX_MS)
                    .isLessThanOrEqualTo(SLA_MAX_MS);

            softly.assertThat(rps)
                    .as("[%s] Throughput minimo (req/s)", escenario)
                    .isGreaterThan(0.0);
        });
    }

    @Test
    public void testRendimientoAI() {
        HttpClient cliente = clienteHttp();
        exigirServicio(cliente);

        // Calentamiento: DNS, conexion y JIT no deben contaminar la medicion.
        boolean conChat = PerfConfig.chatConProveedorLocal(BASE_URL, TOKEN);
        pedir(cliente, "/api/ai/salud", null);
        if (conChat) {
            pedir(cliente, "/api/ai/chat", CUERPO_CHAT);
        }

        List<Long> latencias = new ArrayList<>();
        int errores = 0;
        long inicio = System.nanoTime();

        for (int i = 0; i < MEDICIONES; i++) {
            Lectura salud = pedir(cliente, "/api/ai/salud", null);
            latencias.add(salud.ms());
            if (!salud.ok()) {
                errores++;
            }
            if (conChat) {
                Lectura chat = pedir(cliente, "/api/ai/chat", CUERPO_CHAT);
                latencias.add(chat.ms());
                if (!chat.ok()) {
                    errores++;
                }
            }
        }

        long totalMs = (System.nanoTime() - inicio) / 1_000_000;
        resumir("testRendimientoAI", latencias, errores, totalMs);
        validarSla("testRendimientoAI", latencias, errores, totalMs);
    }

    @Test
    public void testSobrecargaAI() {
        HttpClient cliente = clienteHttp();
        exigirServicio(cliente);

        pedir(cliente, "/api/ai/salud", null);

        System.out.println("[PerformTestMsAI] testSobrecargaAI sobre /api/ai/salud: "
                + PETICIONES_POR_NIVEL + " peticiones por nivel de concurrencia");

        for (int nivel : niveles(CONCURRENCIA_MAX)) {
            ExecutorService pool = Executors.newFixedThreadPool(nivel);
            List<Future<Lectura>> futuros = new ArrayList<>();
            List<Long> latencias = new ArrayList<>();
            int errores = 0;
            long inicio = System.nanoTime();

            for (int i = 0; i < PETICIONES_POR_NIVEL; i++) {
                futuros.add(pool.submit(() -> pedir(cliente, "/api/ai/salud", null)));
            }
            for (Future<Lectura> futuro : futuros) {
                try {
                    Lectura lectura = futuro.get();
                    latencias.add(lectura.ms());
                    if (!lectura.ok()) {
                        errores++;
                    }
                } catch (Exception e) {
                    errores++;
                }
            }
            long total = (System.nanoTime() - inicio) / 1_000_000;
            pool.shutdown();
            resumir("testSobrecargaAI[c=" + nivel + "]", latencias, errores, total);
            validarSla("testSobrecargaAI[c=" + nivel + "]", latencias, errores, total);
        }
    }
}
