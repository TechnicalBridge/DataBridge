package com.tbridge.debt.throughput;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Stress Testing (estres) de ms-debt con Gatling (Java DSL).
 *
 * <p>Subida progresiva TASA → MAX_USUARIOS para hallar donde se degrada. Sin exigir p95 de carga normal: solo se registra y se acota fallidas < 50 %. Objetivo: documentar el techo, no romper el entorno.
 *
 * <p>Endpoints (solo lectura, sin efectos irreversibles):
 * <pre>
 *   GET /actuator/health (publica)
 *   GET /api/debts (JWT) → saveAs deudaId
 *   GET /api/debts/{id} (JWT)
 *   GET /api/debts/{id}/simulate?months=6 (JWT)
 * </pre>
 *  * <p>Sin token solo corre /actuator/health. Para la cartera se necesita JWT de DEBTOR con deuda. Solo lecturas: no repacta ni disputa.
 * <p>Configuracion (propiedad -D / variable de entorno, con omision):
 * <pre>
 *   baseUrl    BASE_URL      http://localhost:8083
 *   token      TOKEN / DEBT_TOKEN   (vacio: solo rutas publicas)
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
 *   .\mvnw.cmd -f ms-debt\pom.xml -Prendimiento gatling:test
 *       -Dgatling.simulationClass=com.tbridge.debt.throughput.StressSimulationMsDebt
 * </pre>
 */
public class StressSimulationMsDebt extends Simulation{

    private static final String BASE_URL = PerfConfig.configuracion("baseUrl", "BASE_URL", "http://localhost:8083");
    private static final String TOKEN = PerfConfig.token("DEBT_TOKEN");

    private static final int MESES = (int) PerfConfig.numero("meses", "MESES", 6);

    private static final double TASA = PerfConfig.numero("tasa", "TASA", 5);
    private static final int RAMPA = PerfConfig.segundos("rampa", "RAMPA", 10);
    private static final int DURACION = PerfConfig.segundos("duracion", "DURACION", 20);
    private static final double MAX_USUARIOS = PerfConfig.numero("usuariosMax", "MAX_USUARIOS", 200);
    private static final double PICO = PerfConfig.numero("pico", "PICO", 100);
    private static final int BASE_SEG = PerfConfig.segundos("baseSeg", "BASE_SEG", 20);
    private static final int SOAK_SEG = PerfConfig.segundos("soakSeg", "SOAK_SEG", 300);

    public StressSimulationMsDebt() {
        if (!PerfConfig.ejecutandoConGatling()) {
            System.out.println("[StressSimulationMsDebt] Instanciada por JUnit: se corre con gatling:test.");
            return;
        }

        HttpProtocolBuilder httpProtocol = PerfConfig.protocolo(BASE_URL, "Gatling-DataBridge-ms-debt");

        ScenarioBuilder salud = scenario("ms-debt - salud")
                .exec(http("GET /actuator/health")
                        .get("/actuator/health")
                        .check(status().is(200)));

        ScenarioBuilder lecturas = scenario("ms-debt - lecturas")
                .exec(http("GET /api/debts")
                        .get("/api/debts")
                        .header("Authorization", "Bearer " + TOKEN)
                        .check(status().is(200))
                        .check(jsonPath("$._embedded.debts[0].id").saveAs("deudaId")))
                .exec(http("GET /api/debts/{id}")
                        .get("/api/debts/#{deudaId}")
                        .header("Authorization", "Bearer " + TOKEN)
                        .check(status().is(200)))
                .exec(http("GET /api/debts/{id}/simulate")
                        .get("/api/debts/#{deudaId}/simulate?months=" + MESES)
                        .header("Authorization", "Bearer " + TOKEN)
                        .check(status().is(200)));

        boolean conToken = !TOKEN.isBlank();
        ScenarioBuilder escenario = conToken ? lecturas : salud;
        System.out.println("[StressSimulationMsDebt] " + (conToken ? "Con token: lecturas." : "Sin token: solo salud."));


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

