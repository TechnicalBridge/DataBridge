package com.tbridge.payments.throughput;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Stress Testing (estres) de ms-payments con Gatling (Java DSL).
 *
 * <p>Subida progresiva TASA → MAX_USUARIOS para hallar donde se degrada. Sin exigir p95 de carga normal: solo se registra y se acota fallidas < 50 %. Objetivo: documentar el techo, no romper el entorno.
 *
 * <p>Endpoints (solo lectura, sin efectos irreversibles):
 * <pre>
 *   GET /actuator/health (publica)
 *   GET /api/payments (JWT)
 * </pre>
 *  * <p>Sin token solo corre /actuator/health. No se usa POST /checkout ni el cobro (efectos irreversibles).
 * <p>Configuracion (propiedad -D / variable de entorno, con omision):
 * <pre>
 *   baseUrl    BASE_URL      http://localhost:8084
 *   token      TOKEN / PAYMENTS_TOKEN   (vacio: solo rutas publicas)
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
 *   .\mvnw.cmd -f ms-payments\pom.xml -Prendimiento gatling:test
 *       -Dgatling.simulationClass=com.tbridge.payments.throughput.StressSimulationMsPayments
 * </pre>
 */
public class StressSimulationMsPayments extends Simulation{

    private static final String BASE_URL = PerfConfig.configuracion("baseUrl", "BASE_URL", "http://localhost:8084");
    private static final String TOKEN = PerfConfig.token("PAYMENTS_TOKEN");


    private static final double TASA = PerfConfig.numero("tasa", "TASA", 5);
    private static final int RAMPA = PerfConfig.segundos("rampa", "RAMPA", 10);
    private static final int DURACION = PerfConfig.segundos("duracion", "DURACION", 20);
    private static final double MAX_USUARIOS = PerfConfig.numero("usuariosMax", "MAX_USUARIOS", 200);
    private static final double PICO = PerfConfig.numero("pico", "PICO", 100);
    private static final int BASE_SEG = PerfConfig.segundos("baseSeg", "BASE_SEG", 20);
    private static final int SOAK_SEG = PerfConfig.segundos("soakSeg", "SOAK_SEG", 300);

    public StressSimulationMsPayments() {
        if (!PerfConfig.ejecutandoConGatling()) {
            System.out.println("[StressSimulationMsPayments] Instanciada por JUnit: se corre con gatling:test.");
            return;
        }

        HttpProtocolBuilder httpProtocol = PerfConfig.protocolo(BASE_URL, "Gatling-DataBridge-ms-payments");

        ScenarioBuilder salud = scenario("ms-payments - salud")
                .exec(http("GET /actuator/health")
                        .get("/actuator/health")
                        .check(status().is(200)));

        ScenarioBuilder lecturas = scenario("ms-payments - pagos")
                .exec(http("GET /api/payments")
                        .get("/api/payments")
                        .header("Authorization", "Bearer " + TOKEN)
                        .check(status().is(200)));

        boolean conToken = !TOKEN.isBlank();
        ScenarioBuilder escenario = conToken ? lecturas : salud;
        System.out.println("[StressSimulationMsPayments] " + (conToken ? "Con token: lecturas." : "Sin token: solo salud."));


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
