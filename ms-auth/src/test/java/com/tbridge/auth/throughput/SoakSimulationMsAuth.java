package com.tbridge.auth.throughput;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Soak Testing (resistencia) de ms-auth con Gatling (Java DSL).
 *
 * <p>Carga constante TASA durante SOAK_SEG para detectar degradacion progresiva, acumulacion de errores y fugas. Correlacionar con /actuator/metrics (heap, hilos, conexiones) antes/despues.
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
 *   .\mvnw.cmd -f ms-auth\pom.xml -Prendimiento gatling:test
 *       -Dgatling.simulationClass=com.tbridge.auth.throughput.SoakSimulationMsAuth
 * </pre>
 */
public class SoakSimulationMsAuth extends Simulation{

    private static final String BASE_URL = PerfConfig.configuracion("baseUrl", "BASE_URL", "http://localhost:8081");
    private static final String TOKEN = PerfConfig.token("AUTH_TOKEN");


    private static final double TASA = PerfConfig.numero("tasa", "TASA", 5);
    private static final int RAMPA = PerfConfig.segundos("rampa", "RAMPA", 10);
    private static final int DURACION = PerfConfig.segundos("duracion", "DURACION", 20);
    private static final double MAX_USUARIOS = PerfConfig.numero("usuariosMax", "MAX_USUARIOS", 200);
    private static final double PICO = PerfConfig.numero("pico", "PICO", 100);
    private static final int BASE_SEG = PerfConfig.segundos("baseSeg", "BASE_SEG", 20);
    private static final int SOAK_SEG = PerfConfig.segundos("soakSeg", "SOAK_SEG", 300);

    public SoakSimulationMsAuth() {
        if (!PerfConfig.ejecutandoConGatling()) {
            System.out.println("[SoakSimulationMsAuth] Instanciada por JUnit: se corre con gatling:test.");
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
        System.out.println("[SoakSimulationMsAuth] " + (conToken ? "Con token: lecturas." : "Sin token: solo salud."));


        setUp(
                escenario.injectOpen(
                        rampUsersPerSec(1).to(TASA).during(Duration.ofSeconds(RAMPA)),
                        constantUsersPerSec(TASA).during(Duration.ofSeconds(SOAK_SEG))
                ).protocols(httpProtocol)
        ).assertions(
                global().failedRequests().percent().lt(1.0),
                global().responseTime().percentile(95).lt(1000),
                global().responseTime().percentile(99).lt(3000)
        );

    }
}
