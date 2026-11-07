package com.tbridge.ai.rendimiento;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Prueba de rendimiento de ms-ai con Gatling.
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
 * <p>Desde la raiz del repositorio:
 *
 * <pre>
 *   ./mvnw -f ms-ai/pom.xml gatling:test \
 *       -Dgatling.simulationClass=com.tbridge.ai.rendimiento.PerformTestMsAI \
 *       -Dtoken="$TOKEN"
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

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .userAgentHeader("Gatling-DataBridge-ms-ai");

    private final ScenarioBuilder salud = scenario("ms-ai - salud")
            .exec(http("GET /api/ai/salud")
                    .get("/api/ai/salud")
                    .check(status().is(200))
                    .check(jsonPath("$.service").is("ms-ai"))
                    .check(jsonPath("$.ok").is("true")))
            .pause(1);

    private final ScenarioBuilder chat = scenario("ms-ai - chat")
            .exec(http("POST /api/ai/chat")
                    .post("/api/ai/chat")
                    .header("Authorization", "Bearer " + TOKEN)
                    .body(StringBody("{\"message\":\"\u00bfCu\u00e1nto debo?\",\"messages\":[]}"))
                    .asJson()
                    .check(status().is(200))
                    .check(jsonPath("$.reply").exists()))
            .pause(1);

    public PerformTestMsAI() {
        if (TOKEN.isBlank()) {
            System.out.println("[PerformTestMsAI] Sin token: solo se ejecuta GET /api/ai/salud.");
            System.out.println("[PerformTestMsAI] Para incluir el chat, pasa -Dtoken=<JWT de un DEBTOR>.");
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
            System.out.println("[PerformTestMsAI] Token detectado: se ejecutan salud y chat.");
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

    @Test
    public void testRendimientoAI() {
        // Gatling simulation is configured via constructor; this is a marker test
        // to allow Surefire to pick up the class. The actual Gatling run uses:
        //   mvn -f ms-ai/pom.xml gatling:test -Dgatling.simulationClass=PerformTestMsAI
        System.out.println("[PerformTestMsAI] testRendimientoAI - marker test for Surefire");
    }
}
