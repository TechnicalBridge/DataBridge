package com.tbridge.auth.troughput;

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
 * Prueba de rendimiento de ms-auth con Gatling y con JUnit.
 *
 * <p>Mide las dos lecturas que el portal y los demas servicios hacen todo el
 * tiempo:
 *
 * <ul>
 *   <li>{@code GET /api/me}: quien soy, con el JWT de la sesion.
 *   <li>{@code GET /.well-known/jwks.json}: las llaves publicas con que los
 *       demas servicios verifican las sesiones.
 * </ul>
 *
 * <p>Sin token solo se ejecuta el JWKS, que es publico. Todo se puede ajustar
 * por propiedad de sistema ({@code -D...}) o por variable de entorno:
 *
 * <pre>
 *   baseUrl    BASE_URL      http://localhost:8081
 *   token      TOKEN         (vacio: sin /api/me)
 *              AUTH_TOKEN    alternativa a TOKEN
 *   tasa       TASA          5 usuarios por segundo
 *   rampa      RAMPA         5 segundos
 *   duracion   DURACION      20 segundos
 * </pre>
 *
 * <p>Para la simulacion de carga con Gatling, desde la raiz del repositorio:
 *
 * <pre>
 *   ./mvnw -f ms-auth/pom.xml gatling:test \
 *       -Dgatling.simulationClass=com.tbridge.auth.troughput.PerformTestMsAuth \
 *       -Dtoken="$TOKEN"
 * </pre>
 *
 * <p>Para las mediciones JUnit (Surefire), con el servicio arriba:
 *
 * <pre>
 *   ./mvnw -f ms-auth/pom.xml test -Dtest=PerformTestMsAuth -DbaseUrl=http://localhost:8081
 * </pre>
 */
public class PerformTestMsAuth extends Simulation {

    private static final String BASE_URL = configuracion("baseUrl", "BASE_URL", "http://localhost:8081");
    private static final String TOKEN = token();

    private static final double TASA = numero("tasa", "TASA", 5);
    private static final int RAMPA = (int) numero("rampa", "RAMPA", 5);
    private static final int DURACION = (int) numero("duracion", "DURACION", 20);

    public PerformTestMsAuth() {
        //  JUnit instancia la clase para correr los @Test; Gatling tambien la
        //  instancia, pero su DSL solo esta disponible cuando manda Gatling.
        if (!ejecutandoConGatling()) {
            System.out.println("[PerformTestMsAuth] Instanciada por JUnit: usa -Dtest=PerformTestMsAuth. "
                    + "La carga completa se corre con gatling:test.");
            return;
        }

        HttpProtocolBuilder httpProtocol = http
                .baseUrl(BASE_URL)
                .acceptHeader("application/json")
                .contentTypeHeader("application/json")
                .userAgentHeader("Gatling-DataBridge-ms-auth");

        ScenarioBuilder jwks = scenario("ms-auth - jwks")
                .exec(http("GET /.well-known/jwks.json")
                        .get("/.well-known/jwks.json")
                        .check(status().is(200))
                        .check(jsonPath("$.keys").exists()))
                .pause(1);

        ScenarioBuilder me = scenario("ms-auth - sesion")
                .exec(http("GET /api/me")
                        .get("/api/me")
                        .header("Authorization", "Bearer " + TOKEN)
                        .check(status().is(200)))
                .pause(1);

        if (TOKEN.isBlank()) {
            System.out.println("[PerformTestMsAuth] Sin token: solo se ejecuta GET /.well-known/jwks.json.");
            System.out.println("[PerformTestMsAuth] Para incluir GET /api/me, pasa -Dtoken=<JWT>.");
            setUp(
                    jwks.injectOpen(
                            rampUsersPerSec(1).to(TASA).during(Duration.ofSeconds(RAMPA)),
                            constantUsersPerSec(TASA).during(Duration.ofSeconds(DURACION))
                    ).protocols(httpProtocol)
            ).assertions(
                    global().failedRequests().percent().lt(1.0),
                    details("GET /.well-known/jwks.json").responseTime().percentile(95).lt(1000)
            );
        } else {
            System.out.println("[PerformTestMsAuth] Token detectado: se ejecutan /api/me y el JWKS.");
            setUp(
                    me.injectOpen(
                            rampUsersPerSec(1).to(TASA).during(Duration.ofSeconds(RAMPA)),
                            constantUsersPerSec(TASA).during(Duration.ofSeconds(DURACION))
                    ).protocols(httpProtocol),
                    jwks.injectOpen(
                            rampUsersPerSec(1).to(TASA).during(Duration.ofSeconds(RAMPA)),
                            constantUsersPerSec(TASA).during(Duration.ofSeconds(DURACION))
                    ).protocols(httpProtocol)
            ).assertions(
                    global().failedRequests().percent().lt(1.0),
                    details("GET /api/me").responseTime().percentile(95).lt(1000),
                    details("GET /.well-known/jwks.json").responseTime().percentile(95).lt(1000)
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
     * El token de la sesion. Se acepta TOKEN o AUTH_TOKEN para no confundirlo
     * con otros tokens del entorno de pruebas.
     */
    private static String token() {
        String valor = System.getProperty("token");
        if (valor == null || valor.isBlank()) {
            valor = System.getenv("TOKEN");
        }
        if (valor == null || valor.isBlank()) {
            valor = System.getenv("AUTH_TOKEN");
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
    // ------------------------------------------------------------------

    private static final int MEDICIONES = (int) numero("mediciones", "MEDICIONES", 30);
    private static final int CONCURRENCIA_MAX = (int) numero("concurrencia", "CONCURRENCIA", 8);
    private static final int PETICIONES_POR_NIVEL = (int) numero("peticiones", "PETICIONES", 20);
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

    private static Lectura pedir(HttpClient cliente, String ruta) {
        HttpRequest.Builder peticion = HttpRequest.newBuilder(URI.create(BASE_URL + ruta))
                .timeout(Duration.ofSeconds(30))
                .header("Accept", "application/json")
                .GET();
        if (!TOKEN.isBlank() && !"/.well-known/jwks.json".equals(ruta)) {
            peticion.header("Authorization", "Bearer " + TOKEN);
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
        Lectura salud = pedir(cliente, "/actuator/health");
        Assumptions.assumeTrue(salud.status() > 0,
                () -> "Sin servicio en " + BASE_URL + ". Levanta ms-auth o pasa -DbaseUrl=...");
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
        System.out.println("[PerformTestMsAuth] " + nombre
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
    public void testRendimientoAuth() {
        HttpClient cliente = clienteHttp();
        exigirServicio(cliente);

        // Calentamiento: DNS, conexion y JIT no deben contaminar la medicion.
        pedir(cliente, "/.well-known/jwks.json");
        if (!TOKEN.isBlank()) {
            pedir(cliente, "/api/me");
        }

        List<Long> latencias = new ArrayList<>();
        int errores = 0;
        long inicio = System.nanoTime();

        for (int i = 0; i < MEDICIONES; i++) {
            Lectura jwks = pedir(cliente, "/.well-known/jwks.json");
            latencias.add(jwks.ms());
            if (!jwks.ok()) {
                errores++;
            }
            if (!TOKEN.isBlank()) {
                Lectura me = pedir(cliente, "/api/me");
                latencias.add(me.ms());
                if (!me.ok()) {
                    errores++;
                }
            }
        }

        long totalMs = (System.nanoTime() - inicio) / 1_000_000;
        resumir("testRendimientoAuth", latencias, errores, totalMs);
        validarSla("testRendimientoAuth", latencias, errores, totalMs);
    }

    @Test
    public void testSobrecargaAuth() {
        HttpClient cliente = clienteHttp();
        exigirServicio(cliente);
        String ruta = TOKEN.isBlank() ? "/.well-known/jwks.json" : "/api/me";

        pedir(cliente, "/.well-known/jwks.json");

        System.out.println("[PerformTestMsAuth] testSobrecargaAuth sobre " + ruta + ": "
                + PETICIONES_POR_NIVEL + " peticiones por nivel de concurrencia");

        for (int nivel : niveles(CONCURRENCIA_MAX)) {
            ExecutorService pool = Executors.newFixedThreadPool(nivel);
            List<Future<Lectura>> futuros = new ArrayList<>();
            List<Long> latencias = new ArrayList<>();
            int errores = 0;
            long inicio = System.nanoTime();

            for (int i = 0; i < PETICIONES_POR_NIVEL; i++) {
                futuros.add(pool.submit(() -> pedir(cliente, ruta)));
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
            resumir("testSobrecargaAuth[c=" + nivel + "]", latencias, errores, total);
            validarSla("testSobrecargaAuth[c=" + nivel + "]", latencias, errores, total);
        }
    }
}
