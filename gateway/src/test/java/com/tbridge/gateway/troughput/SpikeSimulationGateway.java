package com.tbridge.gateway.troughput;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Spike de gateway con Gatling (Java DSL).
 *
 * <p>Base estable TASA, salto rapido a PICO y recuperacion a la base. Mide latencia, errores y recuperacion del reporte Gatling (RPS vs. tiempo).
 *
 * <p>Endpoints (solo lectura, sin efectos irreversibles):
 * <pre>
 *   GET /api/ai/salud (publica, via gateway → ms-ai)
 *   GET /api/me, /api/debts, /api/payments (JWT)
 * </pre>
 *  * <p>Sin token solo corre /api/ai/salud. El limitador por IP (120/min) devuelve 429 en alta concurrencia: subir RATE_GLOBAL_CAPACITY para medir enrutamiento.
 * <p>Configuracion (propiedad -D / variable de entorno, con omision):
 * <pre>
 *   baseUrl    BASE_URL      http://localhost:8082
 *   token      TOKEN / GATEWAY_TOKEN   (vacio: solo rutas publicas)
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
 *   .\mvnw.cmd -f gateway\pom.xml gatling:test
 *       -Dgatling.simulationClass=com.tbridge.gateway.troughput.SpikeSimulationGateway
 * </pre>
 */
public class SpikeSimulationGateway extends Simulation{

    private static final String BASE_URL = PerfConfig.configuracion("baseUrl", "BASE_URL", "http://localhost:8082");
    private static final String TOKEN = PerfConfig.token("GATEWAY_TOKEN");


    private static final double TASA = PerfConfig.numero("tasa", "TASA", 10);
    private static final int RAMPA = PerfConfig.segundos("rampa", "RAMPA", 5);
    private static final int DURACION = PerfConfig.segundos("duracion", "DURACION", 20);
    private static final double MAX_USUARIOS = PerfConfig.numero("usuariosMax", "MAX_USUARIOS", 200);
    private static final double PICO = PerfConfig.numero("pico", "PICO", 100);
    private static final int BASE_SEG = PerfConfig.segundos("baseSeg", "BASE_SEG", 20);
    private static final int SOAK_SEG = PerfConfig.segundos("soakSeg", "SOAK_SEG", 300);

    public SpikeSimulationGateway() {
        if (!PerfConfig.ejecutandoConGatling()) {
            System.out.println("[SpikeSimulationGateway] Instanciada por JUnit: se corre con gatling:test.");
            return;
        }

        HttpProtocolBuilder httpProtocol = PerfConfig.protocolo(BASE_URL, "Gatling-DataBridge-gateway");

        ScenarioBuilder salud = scenario("gateway - rutas publicas")
                .exec(http("GET /api/ai/salud")
                        .get("/api/ai/salud")
                        .check(status().is(200))
                        .check(jsonPath("$.ok").is("true")));

        ScenarioBuilder lecturas = scenario("gateway - todas las rutas")
                .exec(http("GET /api/me")
                        .get("/api/me")
                        .header("Authorization", "Bearer " + TOKEN)
                        .check(status().is(200)))
                .exec(http("GET /api/debts")
                        .get("/api/debts")
                        .header("Authorization", "Bearer " + TOKEN)
                        .check(status().is(200)))
                .exec(http("GET /api/payments")
                        .get("/api/payments")
                        .header("Authorization", "Bearer " + TOKEN)
                        .check(status().is(200)))
                .exec(http("GET /api/ai/salud")
                        .get("/api/ai/salud")
                        .check(status().is(200))
                        .check(jsonPath("$.ok").is("true")));

        boolean conToken = !TOKEN.isBlank();
        ScenarioBuilder escenario = conToken ? lecturas : salud;
        System.out.println("[SpikeSimulationGateway] " + (conToken ? "Con token: lecturas." : "Sin token: solo salud."));


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

