package com.tbridge.ai.throughput;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Stress Testing (estres) de ms-ai con Gatling (Java DSL).
 *
 * <p>Subida progresiva TASA → MAX_USUARIOS para hallar donde se degrada. Sin exigir p95 de carga normal: solo se registra y se acota fallidas < 50 %. Objetivo: documentar el techo, no romper el entorno.
 *
 * <p>Endpoints (solo lectura, sin efectos irreversibles):
 * <pre>
 *   GET /api/ai/salud (publica)
 *   POST /api/ai/chat (JWT DEBTOR; solo con LLM_PROVIDER=local)
 * </pre>
 * <p>Sin token o con un proveedor real (groq, gemini o xai) solo corre la salud: el chat se mide unicamente con LLM_PROVIDER=local, para no gastar cupo.
 * <p>Configuracion (propiedad -D / variable de entorno, con omision):
 * <pre>
 *   baseUrl    BASE_URL      http://localhost:8085
 *   token      TOKEN / AI_TOKEN   (vacio: solo rutas publicas)
 *   tasa       TASA          5
 *   rampa      RAMPA         10 segundos
 *   duracion   DURACION      20 segundos
 *   usuariosMax MAX_USUARIOS  200
 *   pico       PICO          100
 *   baseSeg    BASE_SEG      20
 *   soakSeg    SOAK_SEG      300
 * </pre>
 *
 * <p>Ejecucion (raiz del repositorio):
 * <pre>
 *   .\mvnw.cmd -f ms-ai\pom.xml -Prendimiento gatling:test
 *       -Dgatling.simulationClass=com.tbridge.ai.throughput.StressSimulationMsAI
 * </pre>
 */
public class StressSimulationMsAI extends Simulation{

    private static final String BASE_URL = PerfConfig.configuracion("baseUrl", "BASE_URL", "http://localhost:8085");
    private static final String TOKEN = PerfConfig.token("AI_TOKEN");

    private static final String CUERPO_CHAT = "{\"message\":\"\u00bfCu\u00e1nto debo?\",\"messages\":[]}";

    private static final double TASA = PerfConfig.numero("tasa", "TASA", 5);
    private static final int RAMPA = PerfConfig.segundos("rampa", "RAMPA", 10);
    private static final int DURACION = PerfConfig.segundos("duracion", "DURACION", 20);
    private static final double MAX_USUARIOS = PerfConfig.numero("usuariosMax", "MAX_USUARIOS", 200);
    private static final double PICO = PerfConfig.numero("pico", "PICO", 100);
    private static final int BASE_SEG = PerfConfig.segundos("baseSeg", "BASE_SEG", 20);
    private static final int SOAK_SEG = PerfConfig.segundos("soakSeg", "SOAK_SEG", 300);

    public StressSimulationMsAI() {
        if (!PerfConfig.ejecutandoConGatling()) {
            System.out.println("[StressSimulationMsAI] Instanciada por JUnit: se corre con gatling:test.");
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

        boolean conToken = PerfConfig.chatConProveedorLocal(BASE_URL, TOKEN);
        ScenarioBuilder escenario = conToken ? lecturas : salud;
        System.out.println("[StressSimulationMsAI] " + (conToken ? "Con token y proveedor local: chat." : "Sin chat: solo salud."));


        setUp(
                escenario.injectOpen(
                        rampUsersPerSec(1).to(TASA).during(Duration.ofSeconds(RAMPA)),
                        constantUsersPerSec(TASA).during(Duration.ofSeconds(DURACION)),
                        rampUsersPerSec(TASA).to(MAX_USUARIOS).during(Duration.ofSeconds(RAMPA)),
                        constantUsersPerSec(MAX_USUARIOS).during(Duration.ofSeconds(DURACION))
                ).protocols(httpProtocol)
        ).assertions(
                global().failedRequests().percent().lt(50.0)
        );

    }
}
