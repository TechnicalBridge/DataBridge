package com.tbridge.auth.troughput;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Load Testing (carga) de ms-auth con Gatling (Java DSL).
 *
 * <p>Escalonado 50 â†’ 100 â†’ MAX_USUARIOS (p. ej. 500) usuarios/seg. Mide p50/p95/p99, RPS y errores. Criterio: fallidas < 1 %, p95/p99 configurables.
 *
 * <p>Endpoints (solo lectura, sin efectos irreversibles):
 * <pre>
 *   GET /.well-known/jwks.json (publica)
 *   GET /api/me (JWT)
 * </pre>
 *  * <p>Sin token solo corre el JWKS. No se ejercitan POST /api/auth/* (consumen codigos y sesiones).
 * <p>Configuracion (propiedad -D / variable de entorno, con omision):
 * <pre>
 *   baseUrl    BASE_URL      http://localhost:8081
 *   token      TOKEN / AUTH_TOKEN   (vacio: solo rutas publicas)
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
 *   .\mvnw.cmd -f ms-auth\pom.xml gatling:test
 *       -Dgatling.simulationClass=com.tbridge.auth.troughput.LoadSimulationMsAuth
 * </pre>
 */
public class LoadSimulationMsAuth extends Simulation{

    private static final String BASE_URL = PerfConfig.configuracion("baseUrl", "BASE_URL", "http://localhost:8081");
    private static final String TOKEN = PerfConfig.token("AUTH_TOKEN");


    private static final double TASA = PerfConfig.numero("tasa", "TASA", 50);
    private static final int RAMPA = PerfConfig.segundos("rampa", "RAMPA", 30);
    private static final int DURACION = PerfConfig.segundos("duracion", "DURACION", 30);
        private static final int P95_MAX = (int) PerfConfig.numero("p95Max", "P95_MAX", 6000);
    private static final int P99_MAX = (int) PerfConfig.numero("p99Max", "P99_MAX", 7000);
private static final double MAX_USUARIOS = PerfConfig.numero("usuariosMax", "MAX_USUARIOS", 500);
    private static final double PICO = PerfConfig.numero("pico", "PICO", 100);
    private static final int BASE_SEG = PerfConfig.segundos("baseSeg", "BASE_SEG", 20);
    private static final int SOAK_SEG = PerfConfig.segundos("soakSeg", "SOAK_SEG", 300);

    public LoadSimulationMsAuth() {
        if (!PerfConfig.ejecutandoConGatling()) {
            System.out.println("[LoadSimulationMsAuth] Instanciada por JUnit: se corre con gatling:test.");
            return;
        }

        HttpProtocolBuilder httpProtocol = PerfConfig.protocolo(BASE_URL, "Gatling-DataBridge-ms-auth");

        ScenarioBuilder salud = scenario("ms-auth - jwks")
                .exec(http("GET /.well-known/jwks.json")
                        .get("/.well-known/jwks.json")
                        .check(status().is(200))
                        .check(jsonPath("$.keys").exists()));

        ScenarioBuilder lecturas = scenario("ms-auth - sesion")
                .exec(http("GET /api/me")
                        .get("/api/me")
                        .header("Authorization", "Bearer " + TOKEN)
                        .check(status().is(200)))
                .exec(http("GET /.well-known/jwks.json")
                        .get("/.well-known/jwks.json")
                        .check(status().is(200)));

        boolean conToken = !TOKEN.isBlank();
        ScenarioBuilder escenario = conToken ? lecturas : salud;
        System.out.println("[LoadSimulationMsAuth] " + (conToken ? "Con token: lecturas." : "Sin token: solo salud."));


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
