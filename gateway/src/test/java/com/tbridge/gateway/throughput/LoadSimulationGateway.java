package com.tbridge.gateway.throughput;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Load Testing (carga) de gateway con Gatling (Java DSL).
 *
 * <p>Escalonado 50 â†’ 100 â†’ MAX_USUARIOS (p. ej. 500) usuarios/seg. Mide p50/p95/p99, RPS y errores. Criterio: fallidas < 1 %, p95/p99 configurables.
 *
 * <p>Endpoints (solo lectura, sin efectos irreversibles):
 * <pre>
 *   GET /api/ai/salud (publica, via gateway â†’ ms-ai)
 *   GET /api/me, /api/debts, /api/payments (JWT)
 * </pre>
 *  * <p>Sin token solo corre /api/ai/salud. El limitador por IP (120/min) devuelve 429 en alta concurrencia: subir RATE_GLOBAL_CAPACITY para medir enrutamiento.
 * <p>Configuracion (propiedad -D / variable de entorno, con omision):
 * <pre>
 *   baseUrl    BASE_URL      http://localhost:8082
 *   token      TOKEN / GATEWAY_TOKEN   (vacio: solo rutas publicas)
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
 *   .\mvnw.cmd -f gateway\pom.xml -Prendimiento gatling:test
 *       -Dgatling.simulationClass=com.tbridge.gateway.throughput.LoadSimulationGateway
 * </pre>
 */
public class LoadSimulationGateway extends Simulation{

    private static final String BASE_URL = PerfConfig.configuracion("baseUrl", "BASE_URL", "http://localhost:8082");
    private static final String TOKEN = PerfConfig.token("GATEWAY_TOKEN");


    private static final double TASA = PerfConfig.numero("tasa", "TASA", 50);
    private static final int RAMPA = PerfConfig.segundos("rampa", "RAMPA", 30);
    private static final int DURACION = PerfConfig.segundos("duracion", "DURACION", 30);
        private static final int P95_MAX = (int) PerfConfig.numero("p95Max", "P95_MAX", 6000);
    private static final int P99_MAX = (int) PerfConfig.numero("p99Max", "P99_MAX", 7000);
private static final double MAX_USUARIOS = PerfConfig.numero("usuariosMax", "MAX_USUARIOS", 500);
    private static final double PICO = PerfConfig.numero("pico", "PICO", 100);
    private static final int BASE_SEG = PerfConfig.segundos("baseSeg", "BASE_SEG", 20);
    private static final int SOAK_SEG = PerfConfig.segundos("soakSeg", "SOAK_SEG", 300);

    public LoadSimulationGateway() {
        if (!PerfConfig.ejecutandoConGatling()) {
            System.out.println("[LoadSimulationGateway] Instanciada por JUnit: se corre con gatling:test.");
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
        System.out.println("[LoadSimulationGateway] " + (conToken ? "Con token: lecturas." : "Sin token: solo salud."));


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
