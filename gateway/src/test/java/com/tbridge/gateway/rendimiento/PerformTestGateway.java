package com.tbridge.gateway.rendimiento;

import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;

import static io.gatling.javaapi.core.CoreDsl.*;
import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Prueba de rendimiento del gateway con Gatling.
 *
 * <p>Mide lo que cuesta enrutar, no lo que cuesta cada servicio: una peticion a
 * cada familia de rutas, con la misma sesion, para ver si alguna se queda
 * pegada. Las cuatro rutas van a servicios distintos:
 *
 * <ul>
 *   <li>{@code GET /api/me}: ms-auth.
 *   <li>{@code GET /api/debts}: ms-debt.
 *   <li>{@code GET /api/payments}: ms-payments.
 *   <li>{@code GET /api/ai/salud}: ms-ai (publica).
 * </ul>
 *
 * <p>Sin token solo se ejecuta la ruta publica {@code /api/ai/salud}. Todo se
 * puede ajustar por propiedad de sistema ({@code -D...}) o por variable de
 * entorno:
 *
 * <pre>
 *   baseUrl    BASE_URL       http://localhost:8082
 *   token      TOKEN          (vacio: solo rutas publicas)
 *              GATEWAY_TOKEN  alternativa a TOKEN
 *   tasa       TASA           5 usuarios por segundo
 *   rampa      RAMPA          5 segundos
 *   duracion   DURACION       20 segundos
 * </pre>
 *
 * <p>Desde la raiz del repositorio:
 *
 * <pre>
 *   ./mvnw -f gateway/pom.xml gatling:test \
 *       -Dgatling.simulationClass=com.tbridge.gateway.rendimiento.PerformTestGateway \
 *       -Dtoken="$TOKEN"
 * </pre>
 *
 * <p>El limitador del gateway es de 120 peticiones por minuto y cliente; para
 * medir el enrutamiento hay que subir {@code RATE_GLOBAL_CAPACITY} en el
 * gateway antes de correr la prueba.
 */
public class PerformTestGateway extends Simulation {

    private static final String BASE_URL = configuracion("baseUrl", "BASE_URL", "http://localhost:8082");
    private static final String TOKEN = token();

    private static final double TASA = numero("tasa", "TASA", 5);
    private static final int RAMPA = (int) numero("rampa", "RAMPA", 5);
    private static final int DURACION = (int) numero("duracion", "DURACION", 20);

    private final HttpProtocolBuilder httpProtocol = http
            .baseUrl(BASE_URL)
            .acceptHeader("application/json")
            .contentTypeHeader("application/json")
            .userAgentHeader("Gatling-DataBridge-gateway");

    private final ScenarioBuilder publicas = scenario("gateway - rutas publicas")
            .exec(http("GET /api/ai/salud")
                    .get("/api/ai/salud")
                    .check(status().is(200))
                    .check(jsonPath("$.service").is("ms-ai"))
                    .check(jsonPath("$.ok").is("true")))
            .pause(1);

    private final ScenarioBuilder completas = scenario("gateway - todas las rutas")
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
                    .check(jsonPath("$.service").is("ms-ai"))
                    .check(jsonPath("$.ok").is("true")))
            .pause(1);

    public PerformTestGateway() {
        if (TOKEN.isBlank()) {
            System.out.println("[PerformTestGateway] Sin token: solo se ejecuta GET /api/ai/salud.");
            System.out.println("[PerformTestGateway] Para medir el resto de rutas, pasa -Dtoken=<JWT>.");
            setUp(
                    publicas.injectOpen(
                            rampUsersPerSec(1).to(TASA).during(Duration.ofSeconds(RAMPA)),
                            constantUsersPerSec(TASA).during(Duration.ofSeconds(DURACION))
                    ).protocols(httpProtocol)
            ).assertions(
                    global().failedRequests().percent().lt(1.0),
                    details("GET /api/ai/salud").responseTime().percentile(95).lt(1000)
            );
        } else {
            System.out.println("[PerformTestGateway] Token detectado: se ejecutan las cuatro rutas.");
            setUp(
                    completas.injectOpen(
                            rampUsersPerSec(1).to(TASA).during(Duration.ofSeconds(RAMPA)),
                            constantUsersPerSec(TASA).during(Duration.ofSeconds(DURACION))
                    ).protocols(httpProtocol)
            ).assertions(
                    global().failedRequests().percent().lt(1.0),
                    details("GET /api/me").responseTime().percentile(95).lt(1000),
                    details("GET /api/debts").responseTime().percentile(95).lt(2000),
                    details("GET /api/payments").responseTime().percentile(95).lt(2000),
                    details("GET /api/ai/salud").responseTime().percentile(95).lt(1000)
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
     * El token de la sesion. Se acepta TOKEN o GATEWAY_TOKEN para no
     * confundirlo con otros tokens del entorno de pruebas.
     */
    private static String token() {
        String valor = System.getProperty("token");
        if (valor == null || valor.isBlank()) {
            valor = System.getenv("TOKEN");
        }
        if (valor == null || valor.isBlank()) {
            valor = System.getenv("GATEWAY_TOKEN");
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
