package com.tbridge.ai.troughput;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Spike de ms-ai con Gatling (Java DSL).
 *
 * <p>Base estable TASA, salto rapido a PICO y recuperacion a la base. Mide latencia, errores y recuperacion del reporte Gatling (RPS vs. tiempo).
 *
 * <p>Endpoints (solo lectura, sin efectos irreversibles):
 * <pre>
 *   GET /api/ai/salud (publica)
 *   POST /api/ai/chat (JWT DEBTOR, fallback local, sin LLM real)
 * </pre>
 *  * <p>Sin token solo corre la salud. El chat nunca llama a Groq/Gemini/xAI reales: con LLM no configurado responde con el motor local tras leer ms-debt.
 * <p>Configuracion (propiedad -D / variable de entorno, con omision):
 * <pre>
 *   baseUrl    BASE_URL      http://localhost:8085
 *   token      TOKEN / AI_TOKEN   (vacio: solo rutas publicas)
 *   tasa       TASA          10
 *   rampa      RAMPA         5 segundos
 *   duracion   DURACION      20 segundos
 *   usuariosMax MAX_USUARIOS  200
 *   pico       PICO          100
 *   baseSeg    BASE_SEG      20
 *   soakSeg    SOAK_SEG      300
 * </pre>
 *
 * <p>Ejecucion (raiz del repositorio):
 * <pre>
 *   .\mvnw.cmd -f ms-ai\pom.xml gatling:test
 *       -Dgatling.simulationClass=com.tbridge.ai.troughput.SpikeSimulationMsAI
 * </pre>
 */
public class SpikeSimulationMsAI extends Simulation{

    private static final String BASE_URL = PerfConfig.configuracion("baseUrl", "BASE_URL", "http://localhost:8085");
    private static final String TOKEN = PerfConfig.token("AI_TOKEN");

    private static final String CUERPO_CHAT = "{\"message\":\"\u00bfCu\u00e1nto debo?\",\"messages\":[]}";

    private static final double TASA = PerfConfig.numero("tasa", "TASA", 10);
    private static final int RAMPA = PerfConfig.segundos("rampa", "RAMPA", 5);
    private static final int DURACION = PerfConfig.segundos("duracion", "DURACION", 20);
    private static final double MAX_USUARIOS = PerfConfig.numero("usuariosMax", "MAX_USUARIOS", 200);
    private static final double PICO = PerfConfig.numero("pico", "PICO", 100);
    private static final int BASE_SEG = PerfConfig.segundos("baseSeg", "BASE_SEG", 20);
    private static final int SOAK_SEG = PerfConfig.segundos("soakSeg", "SOAK_SEG", 300);

    public SpikeSimulationMsAI() {
        if (!PerfConfig.ejecutandoConGatling()) {
            System.out.println("[SpikeSimulationMsAI] Instanciada por JUnit: se corre con gatling:test.");
            return;
        }

        HttpProtocolBuilder httpProtocol = PerfConfig.protocolo(BASE_URL, "Gatling-DataBridge-ms-ai");

        ScenarioBuilder salud = scenario("ms-ai - salud")
                .exec(http("GET /api/ai/salud")
                        .get("/api/ai/salud")
                        .check(status().is(200))
                        .check(jsonPath("$.ok").is("true")));

        ScenarioBuilder lecturas = scenario("ms-ai - chat")
                .exec(http("POST /api/ai/chat")
                        .post("/api/ai/chat")
                        .header("Authorization", "Bearer " + TOKEN)
                        .body(StringBody(CUERPO_CHAT))
                        .asJson()
                        .check(status().is(200))
                        .check(jsonPath("$.reply").exists()));

        boolean conToken = !TOKEN.isBlank();
        ScenarioBuilder escenario = conToken ? lecturas : salud;
        System.out.println("[SpikeSimulationMsAI] " + (conToken ? "Con token: lecturas." : "Sin token: solo salud."));


        setUp(
                escenario.injectOpen(
                        rampUsersPerSec(1).to(TASA).during(Duration.ofSeconds(RAMPA)),
                        constantUsersPerSec(TASA).during(Duration.ofSeconds(BASE_SEG)),
                        rampUsersPerSec(TASA).to(PICO).during(Duration.ofSeconds(RAMPA)),
                        constantUsersPerSec(PICO).during(Duration.ofSeconds(DURACION)),
                        rampUsersPerSec(PICO).to(TASA).during(Duration.ofSeconds(RAMPA)),
                        constantUsersPerSec(TASA).during(Duration.ofSeconds(BASE_SEG))
                ).protocols(httpProtocol)
        ).assertions(
                global().failedRequests().percent().lt(5.0),
                global().responseTime().percentile(95).lt(1000)
        );

    }
}

