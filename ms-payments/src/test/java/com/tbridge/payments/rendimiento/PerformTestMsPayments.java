package com.tbridge.payments.rendimiento;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Prueba de rendimiento de ms-payments con Gatling.
 *
 * <p>Mide el historial que el portal pide cada vez que alguien entra a "Mis
 * pagos": {@code GET /api/payments}. Es solo lectura: no abre cobros, no
 * confirma pagos ni deja basura en la base, asi que se puede repetir.
 *
 * <p>Sin token solo se ejecuta {@code /actuator/health}, que es publico. Todo se
 * puede ajustar por propiedad de sistema ({@code -D...}) o por variable de
 * entorno:
 *
 * <pre>
 *   baseUrl    BASE_URL        http://localhost:8084
 *   token      TOKEN           (vacio: solo /actuator/health)
 *              PAYMENTS_TOKEN  alternativa a TOKEN
 *   tasa       TASA            5 usuarios por segundo
 *   rampa      RAMPA           5 segundos
 *   duracion   DURACION        20 segundos
 * </pre>
 *
 * <p>Desde la raiz del repositorio:
 *
 * <pre>
 *   ./mvnw -f ms-payments/pom.xml gatling:test \
 *       -Dgatling.simulationClass=com.tbridge.payments.rendimiento.PerformTestMsPayments \
 *       -Dtoken="$TOKEN"
 * </pre>
 */
public class PerformTestMsPayments extends Simulation {

    private static final String BASE_URL = configuracion("baseUrl", "BASE_URL", "http://localhost:8084");
    private static final String TOKEN = token();

    private static final double TASA = numero("tasa", "TASA", 5);
    private static final int RAMPA = (int) numero("rampa", "RAMPA", 5);
    private static final int DURACION = (int) numero("duracion", "DURACION", 20);

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .userAgentHeader("Gatling-DataBridge-ms-payments");

    private final ScenarioBuilder salud = scenario("ms-payments - salud")
            .exec(http("GET /actuator/health")
                    .get("/actuator/health")
                    .check(status().is(200))
                    .check(jsonPath("$.status").is("UP")))
            .pause(1);

    private final ScenarioBuilder lecturas = scenario("ms-payments - pagos")
            .exec(http("GET /api/payments")
                    .get("/api/payments")
                    .header("Authorization", "Bearer " + TOKEN)
                    .check(status().is(200)))
            .pause(1);

    public PerformTestMsPayments() {
        if (TOKEN.isBlank()) {
            System.out.println("[PerformTestMsPayments] Sin token: solo se ejecuta GET /actuator/health.");
            System.out.println("[PerformTestMsPayments] Para medir los pagos, pasa -Dtoken=<JWT>.");
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
            System.out.println("[PerformTestMsPayments] Token detectado: se ejecuta GET /api/payments.");
            setUp(
                    lecturas.injectOpen(
                            rampUsersPerSec(1).to(TASA).during(Duration.ofSeconds(RAMPA)),
                            constantUsersPerSec(TASA).during(Duration.ofSeconds(DURACION))
                    ).protocols(httpProtocol)
            ).assertions(
                    global().failedRequests().percent().lt(1.0),
                    details("GET /api/payments").responseTime().percentile(95).lt(1000)
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
     * El token de la sesion. Se acepta TOKEN o PAYMENTS_TOKEN para no
     * confundirlo con otros tokens del entorno de pruebas.
     */
    private static String token() {
        String valor = System.getProperty("token");
        if (valor == null || valor.isBlank()) {
            valor = System.getenv("TOKEN");
        }
        if (valor == null || valor.isBlank()) {
            valor = System.getenv("PAYMENTS_TOKEN");
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
