package com.tbridge.payments.integration;

import com.tbridge.payments.model.Payment;
import com.tbridge.payments.model.PaymentEvent;
import com.tbridge.payments.repository.PaymentEventRepository;
import com.tbridge.payments.repository.PaymentRepository;
import com.tbridge.payments.security.JwtTestConfig;
import com.tbridge.payments.security.JwtTestIssuer;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.MySQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Base de las pruebas de autorizacion horizontal de ms-payments contra MySQL
 * real. Ninguna prueba llama a una pasarela ni a ms-debt: esas salidas quedan
 * simuladas o apuntan a una direccion que no existe.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(JwtTestConfig.class)
@Transactional
@Rollback(true)
public abstract class BasePagosMySql {

    protected static final String RUT_ACREEDOR = "76418902-7";
    protected static final String RUT_DEUDOR_A = "16482337-7";
    protected static final String RUT_DEUDOR_B = "18905214-6";

    /** Corre con la base externa de {@code DB_TEST_*} o en CI, donde si hay Docker. */
    public static boolean hayBaseDeDatos() {
        return System.getenv("DB_TEST_URL") != null || "true".equalsIgnoreCase(System.getenv("CI"));
    }

    private static MySQLContainer<?> MYSQL;

    protected static synchronized MySQLContainer<?> contenedor() {
        if (MYSQL == null) {
            MYSQL = new MySQLContainer<>("mysql:8.4")
                    .withDatabaseName("tb_payments")
                    .withUsername("tbridge")
                    .withPassword("tbridge-test")
                    //  En Windows, la inicializacion con el redo log por omision
                    //  tarda varios minutos; con 256 MB arranca en ~90 s.
                    .withCommand("--innodb-redo-log-capacity=268435456")
                    //  En Windows, la inicializacion tarda mas que el timeout
                    //  JDBC por omision (120 s); sin esto el contenedor se mata
                    //  a mitad de la inicializacion y el reintento empieza de cero.
                    .withStartupTimeoutSeconds(480)
                    .withStartupTimeout(java.time.Duration.ofMinutes(8));
            MYSQL.start();
        }
        return MYSQL;
    }

    @DynamicPropertySource
    static void baseDeDatos(DynamicPropertyRegistry propiedades) {
        if (System.getenv("DB_TEST_URL") == null) {
            MySQLContainer<?> mysql = contenedor();
            propiedades.add("spring.datasource.url", mysql::getJdbcUrl);
            propiedades.add("spring.datasource.username", mysql::getUsername);
            propiedades.add("spring.datasource.password", mysql::getPassword);
        } else {
            propiedades.add("spring.datasource.url", () -> System.getenv("DB_TEST_URL"));
            propiedades.add("spring.datasource.username",
                    () -> System.getenv().getOrDefault("DB_TEST_USER", "tbridge"));
            propiedades.add("spring.datasource.password", () -> System.getenv("DB_TEST_PASSWORD"));
        }
        propiedades.add("spring.flyway.enabled", () -> "true");
        propiedades.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:1/.well-known/jwks.json");
        propiedades.add("spring.main.allow-bean-definition-overriding", () -> "true");

        //  Sin salidas reales: pasarelas simuladas, ms-debt en una direccion
        //  que no responde y las tareas de fondo lo mas lejos posible.
        propiedades.add("app.public-url", () -> "http://localhost:8080");
        propiedades.add("app.debt-url", () -> "http://127.0.0.1:1");
        propiedades.add("app.internal-key", () -> "clave-interna-de-prueba");
        propiedades.add("app.webhook-secret", () -> "secreto-de-webhook-de-prueba");
        propiedades.add("app.dispatch-interval-ms", () -> "3600000");
        propiedades.add("app.uf.cron", () -> "0 0 0 1 1 *");
        propiedades.add("app.khipu.url", () -> "http://khipu.invalid");
        propiedades.add("app.khipu.llave", () -> "");
        propiedades.add("app.mercadopago.url", () -> "http://mercadopago.invalid");
        propiedades.add("app.mercadopago.access-token", () -> "");
        propiedades.add("app.mercadopago.public-key", () -> "");
        propiedades.add("app.transbank.environment", () -> "SIMULADA");
        propiedades.add("app.transbank.url", () -> "http://webpay.invalid");
        propiedades.add("events.rabbit", () -> "false");
        //  El perfil de pruebas no define RABBIT_PASSWORD y RabbitProperties lo
        //  resuelve al arrancar aunque el bus este apagado.
        propiedades.add("spring.rabbitmq.password", () -> "prueba");
        propiedades.add("spring.rabbitmq.listener.simple.auto-startup", () -> "false");
        propiedades.add("management.health.rabbit.enabled", () -> "false");
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected JwtTestIssuer issuer;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected Flyway flyway;
    @Autowired protected PaymentRepository pagos;
    @Autowired protected PaymentEventRepository eventos;

    /** Sufijo unico por prueba: los ids de deuda no tienen clave foranea en esta base. */
    protected final String sufijo = UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void prepararDatos() {
        eventos.deleteAll();
        pagos.deleteAll();
    }

    protected Payment crearPago(String rutDeudor, String creditorRut, long monto) {
        Payment pago = new Payment();
        pago.setDebtId(Math.abs((long) (sufijo.hashCode() * 31L + monto)));
        pago.setDebtorRut(rutDeudor);
        pago.setCreditorRut(creditorRut);
        pago.setAmount(new BigDecimal(monto).setScale(2));
        pago.setCurrency(Payment.Currency.CLP);
        pago.setAmountClp(monto);
        pago.setGateway(Payment.Gateway.khipu);
        pago.setGatewayTxnId("txn-" + sufijo + "-" + monto);
        pago.setStatus(Payment.Status.paid);
        pago.setCreatedAt(Instant.now());
        pago.setPaidAt(Instant.now());
        pago = pagos.save(pago);
        eventos.save(PaymentEvent.de(pago.getId(), PaymentEvent.Type.created, PaymentEvent.Source.portal));
        eventos.save(PaymentEvent.de(pago.getId(), PaymentEvent.Type.paid, PaymentEvent.Source.portal));
        return pago;
    }

    protected String tokenDeudor(String rut) {
        return issuer.issue(rut, rut + "@correo.cl", "DEBTOR", "Deudor", rut);
    }

    protected String tokenAcreedor() {
        return issuer.issue("empresa-1", "empresa@correo.cl", "CREDITOR", "Patrimonio", RUT_ACREEDOR);
    }
}
