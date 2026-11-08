package com.tbridge.ai.troughput;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Load Testing (carga) de ms-ai con Gatling (Java DSL).
 *
 * <p>Escalonado 50 â†’ 100 â†’ MAX_USUARIOS (p. ej. 500) usuarios/seg. Mide p50/p95/p99, RPS y errores. Criterio: fallidas < 1 %, p95/p99 configurables.
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
 *   tasa       TASA          50
 *   rampa      RAMPA         30 segundos
 *   duracion   DURACION      30 segundos
 *   usuariosMax MAX_USUARIOS  500
 *   pico       PICO          100
 *   baseSeg    BASE_SEG      20
 *   soakSeg    SOAK_SEG      300
 * </pre>
 *
 * <p>Ejecucion (raiz del repositorio):
 * <pre>
 *   .\mvnw.cmd -f ms-ai\pom.xml gatling:test
 *       -Dgatling.simulationClass=com.tbridge.ai.troughput.LoadSimulationMsAI
 * </pre>
 */
public class LoadSimulationMsAI extends Simulation{

    private static final String BASE_URL = PerfConfig.configuracion("baseUrl", "BASE_URL", "http://localhost:8085");
    private static final String TOKEN = PerfConfig.token("AI_TOKEN");

    private static final String CUERPO_CHAT = "{\"message\":\"\u00bfCu\u00e1nto debo?\",\"messages\":[]}";

    private static final double TASA = PerfConfig.numero("tasa", "TASA", 50);
    private static final int RAMPA = PerfConfig.segundos("rampa", "RAMPA", 30);
    private static final int DURACION = PerfConfig.segundos("duracion", "DURACION", 30);
        private static final int P95_MAX = (int) PerfConfig.numero("p95Max", "P95_MAX", 6000);
    private static final int P99_MAX = (int) PerfConfig.numero("p99Max", "P99_MAX", 7000);
private static final double MAX_USUARIOS = PerfConfig.numero("usuariosMax", "MAX_USUARIOS", 500);
    private static final double PICO = PerfConfig.numero("pico", "PICO", 100);
    private static final int BASE_SEG = PerfConfig.segundos("baseSeg", "BASE_SEG", 20);
    private static final int SOAK_SEG = PerfConfig.segundos("soakSeg", "SOAK_SEG", 300);

    public LoadSimulationMsAI() {
        if (!PerfConfig.ejecutandoConGatling()) {
            System.out.println("[LoadSimulationMsAI] Instanciada por JUnit: se corre con gatling:test.");
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
        System.out.println("[LoadSimulationMsAI] " + (conToken ? "Con token: lecturas." : "Sin token: solo salud."));


        setUp(
                escenario.injectOpen(
                        rampUsersPerSec(1).to(50).during(Duration.ofSeconds(RAMPA)),
                        constantUsersPerSec(50).during(Duration.ofSeconds(DURACION)),
                        rampUsersPerSec(50).to(100).during(Duration.ofSeconds(RAMPA)),
                        constantUsersPerSec(100).during(Duration.ofSeconds(DURACION)),
                        rampUsersPerSec(100).to(MAX_USUARIOS).during(Duration.ofSeconds(RAMPA)),
                        constantUsersPerSec(MAX_USUARIOS).during(Duration.ofSeconds(DURACION))
                ).protocols(httpProtocol)
        ).assertions(
                global().failedRequests().percent().lt(1.0),
                global().responseTime().percentile(95).lt(P95_MAX),
                global().responseTime().percentile(99).lt(P99_MAX)
        );

    }
}
