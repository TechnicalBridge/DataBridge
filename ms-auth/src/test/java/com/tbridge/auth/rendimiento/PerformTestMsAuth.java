package com.tbridge.auth.rendimiento;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Prueba de rendimiento de ms-auth con Gatling.
 *
 * <p>Mide las dos lecturas que el portal y los demas servicios hacen todo el
 * tiempo:
 *
 * <ul>
 *   <li>{@code GET /api/me}: quien soy, con el JWT de la sesion.
 *   <li>{@code GET /.well-known/jwks.json}: las llaves publicas con que los
 *       demas servicios verifican las sesiones.
 * </ul>
 *
 * <p>Sin token solo se ejecuta el JWKS, que es publico. Todo se puede ajustar
 * por propiedad de sistema ({@code -D...}) o por variable de entorno:
 *
 * <pre>
 *   baseUrl    BASE_URL      http://localhost:8081
 *   token      TOKEN         (vacio: sin /api/me)
 *              AUTH_TOKEN    alternativa a TOKEN
 *   tasa       TASA          5 usuarios por segundo
 *   rampa      RAMPA         5 segundos
 *   duracion   DURACION      20 segundos
 * </pre>
 *
 * <p>Desde la raiz del repositorio:
 *
 * <pre>
 *   ./mvnw -f ms-auth/pom.xml gatling:test \
 *       -Dgatling.simulationClass=com.tbridge.auth.rendimiento.PerformTestMsAuth \
 *       -Dtoken="$TOKEN"
 * </pre>
 */
public class PerformTestMsAuth extends Simulation {

    private static final String BASE_URL = configuracion("baseUrl", "BASE_URL", "http://localhost:8081");
    private static final String TOKEN = token();

    private static final double TASA = numero("tasa", "TASA", 5);
    private static final int RAMPA = (int) numero("rampa", "RAMPA", 5);
    private static final int DURACION = (int) numero("duracion", "DURACION", 20);

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .userAgentHeader("Gatling-DataBridge-ms-auth");

    private final ScenarioBuilder jwks = scenario("ms-auth - jwks")
            .exec(http("GET /.well-known/jwks.json")
                    .get("/.well-known/jwks.json")
                    .check(status().is(200))
                    .check(jsonPath("$.keys").exists()))
            .pause(1);

    private final ScenarioBuilder me = scenario("ms-auth - sesion")
            .exec(http("GET /api/me")
                    .get("/api/me")
                    .header("Authorization", "Bearer " + TOKEN)
                    .check(status().is(200)))
            .pause(1);

    public PerformTestMsAuth() {
        if (TOKEN.isBlank()) {
            System.out.println("[PerformTestMsAuth] Sin token: solo se ejecuta GET /.well-known/jwks.json.");
            System.out.println("[PerformTestMsAuth] Para incluir GET /api/me, pasa -Dtoken=<JWT>.");
            setUp(
                    jwks.injectOpen(
                            rampUsersPerSec(1).to(TASA).during(Duration.ofSeconds(RAMPA)),
                            constantUsersPerSec(TASA).during(Duration.ofSeconds(DURACION))
                    ).protocols(httpProtocol)
            ).assertions(
                    global().failedRequests().percent().lt(1.0),
                    details("GET /.well-known/jwks.json").responseTime().percentile(95).lt(1000)
            );
        } else {
            System.out.println("[PerformTestMsAuth] Token detectado: se ejecutan /api/me y el JWKS.");
            setUp(
                    me.injectOpen(
                            rampUsersPerSec(1).to(TASA).during(Duration.ofSeconds(RAMPA)),
                            constantUsersPerSec(TASA).during(Duration.ofSeconds(DURACION))
                    ).protocols(httpProtocol),
                    jwks.injectOpen(
                            rampUsersPerSec(1).to(TASA).during(Duration.ofSeconds(RAMPA)),
                            constantUsersPerSec(TASA).during(Duration.ofSeconds(DURACION))
                    ).protocols(httpProtocol)
            ).assertions(
                    global().failedRequests().percent().lt(1.0),
                    details("GET /api/me").responseTime().percentile(95).lt(1000),
                    details("GET /.well-known/jwks.json").responseTime().percentile(95).lt(1000)
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
     * El token de la sesion. Se acepta TOKEN o AUTH_TOKEN para no confundirlo
     * con otros tokens del entorno de pruebas.
     */
    private static String token() {
        String valor = System.getProperty("token");
        if (valor == null || valor.isBlank()) {
            valor = System.getenv("TOKEN");
        }
        if (valor == null || valor.isBlank()) {
            valor = System.getenv("AUTH_TOKEN");
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
}
