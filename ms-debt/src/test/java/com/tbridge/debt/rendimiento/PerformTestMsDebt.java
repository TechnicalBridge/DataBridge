package com.tbridge.debt.rendimiento;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Prueba de rendimiento de ms-debt con Gatling.
 *
 * <p>Mide las tres lecturas de deuda que de verdad se usan, en este orden:
 *
 * <ul>
 *   <li>{@code GET /api/debts}: la cartera de quien pregunta.
 *   <li>{@code GET /api/debts/{id}}: el detalle de la primera deuda.
 *   <li>{@code GET /api/debts/{id}/simulate?months=6}: el simulador de cuotas.
 * </ul>
 *
 * <p>Solo lecturas: no repacta, no disputa ni escribe nada. La primera peticion
 * toma el id de la primera deuda de la lista; por eso el token tiene que ser de
 * un deudor con cartera. Sin token solo se ejecuta {@code /actuator/health},
 * que es publico.
 *
 * <p>Todo se puede ajustar por propiedad de sistema ({@code -D...}) o por
 * variable de entorno:
 *
 * <pre>
 *   baseUrl    BASE_URL      http://localhost:8083
 *   token      TOKEN         (vacio: solo /actuator/health)
 *              DEBT_TOKEN    alternativa a TOKEN
 *   tasa       TASA          2 usuarios por segundo
 *   rampa      RAMPA         5 segundos
 *   duracion   DURACION      20 segundos
 *   meses      MESES         6 (entre 3 y 24)
 * </pre>
 *
 * <p>Desde la raiz del repositorio:
 *
 * <pre>
 *   ./mvnw -f ms-debt/pom.xml gatling:test \
 *       -Dgatling.simulationClass=com.tbridge.debt.rendimiento.PerformTestMsDebt \
 *       -Dtoken="$TOKEN"
 * </pre>
 */
public class PerformTestMsDebt extends Simulation {

    private static final String BASE_URL = configuracion("baseUrl", "BASE_URL", "http://localhost:8083");
    private static final String TOKEN = token();

    private static final double TASA = numero("tasa", "TASA", 2);
    private static final int RAMPA = (int) numero("rampa", "RAMPA", 5);
    private static final int DURACION = (int) numero("duracion", "DURACION", 20);
    private static final int MESES = (int) numero("meses", "MESES", 6);

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .userAgentHeader("Gatling-DataBridge-ms-debt");

    private final ScenarioBuilder salud = scenario("ms-debt - salud")
            .exec(http("GET /actuator/health")
                    .get("/actuator/health")
                    .check(status().is(200))
                    .check(jsonPath("$.status").is("UP")))
            .pause(1);

    private final ScenarioBuilder lecturas = scenario("ms-debt - lecturas")
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
                    .check(status().is(200)))
            .pause(1);

    public PerformTestMsDebt() {
        if (TOKEN.isBlank()) {
            System.out.println("[PerformTestMsDebt] Sin token: solo se ejecuta GET /actuator/health.");
            System.out.println("[PerformTestMsDebt] Para medir la cartera, pasa -Dtoken=<JWT de un DEBTOR>.");
            setUp(
                    salud.injectOpen(
                            rampUsersPerSec(1).to(TASA).during(Duration.ofSeconds(RAMPA)),
                            constantUsersPerSec(TASA).during(Duration.ofSeconds(DURACION))
                    ).protocols(httpProtocol)
            ).assertions(
                    global().failedRequests().percent().lt(1.0),
                    details("GET /actuator/health").responseTime().percentile(95).lt(1000)
            );
        } else {
            System.out.println("[PerformTestMsDebt] Token detectado: se ejecutan lista, detalle y simulacion.");
            setUp(
                    lecturas.injectOpen(
                            rampUsersPerSec(1).to(TASA).during(Duration.ofSeconds(RAMPA)),
                            constantUsersPerSec(TASA).during(Duration.ofSeconds(DURACION))
                    ).protocols(httpProtocol)
            ).assertions(
                    global().failedRequests().percent().lt(1.0),
                    details("GET /api/debts").responseTime().percentile(95).lt(1000),
                    details("GET /api/debts/{id}").responseTime().percentile(95).lt(1000),
                    details("GET /api/debts/{id}/simulate").responseTime().percentile(95).lt(1000)
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
     * El token de la sesion. Se acepta TOKEN o DEBT_TOKEN para no confundirlo
     * con otros tokens del entorno de pruebas.
     */
    private static String token() {
        String valor = System.getProperty("token");
        if (valor == null || valor.isBlank()) {
            valor = System.getenv("TOKEN");
        }
        if (valor == null || valor.isBlank()) {
            valor = System.getenv("DEBT_TOKEN");
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
