package com.tbridge.debt.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tbridge.debt.model.Batch;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.DebtEvent;
import com.tbridge.debt.model.Installment;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.BatchRepository;
import com.tbridge.debt.repository.CampaignRepository;
import com.tbridge.debt.repository.DebtEventRepository;
import com.tbridge.debt.repository.DebtRepository;
import com.tbridge.debt.repository.InstallmentRepository;
import com.tbridge.debt.repository.OrganizationRepository;
import com.tbridge.debt.security.JwtTestConfig;
import com.tbridge.debt.security.JwtTestIssuer;
import com.tbridge.debt.service.CarteraIntakeService;
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
import java.time.LocalDate;
import java.util.UUID;

/**
 * Base de las pruebas de autorizacion horizontal de ms-debt contra MySQL real.
 *
 * <p>En CI, Testcontainers levanta {@code mysql:8.4} y Flyway aplica las
 * migraciones. En local se puede apuntar a un MySQL ya levantado con
 * {@code DB_TEST_URL}, {@code DB_TEST_USER} y {@code DB_TEST_PASSWORD}; sin
 * base y fuera de CI, las clases que heredan se omiten.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(JwtTestConfig.class)
@Transactional
@Rollback(true)
public abstract class BaseIdorMySql {

    protected static final String RUT_ACREEDOR = "76418902-7";
    protected static final String RUT_DEUDOR_A = "16482337-7";
    protected static final String RUT_DEUDOR_B = "18905214-6";
    protected static final String CLAVE_INTERNA = "clave-interna-de-prueba";

    /** Corre con la base externa de {@code DB_TEST_*} o en CI, donde si hay Docker. */
    public static boolean hayBaseDeDatos() {
        return System.getenv("DB_TEST_URL") != null || "true".equalsIgnoreCase(System.getenv("CI"));
    }

    private static MySQLContainer<?> MYSQL;

    protected static synchronized MySQLContainer<?> contenedor() {
        if (MYSQL == null) {
            MYSQL = new MySQLContainer<>("mysql:8.4")
                    .withDatabaseName("tb_debt")
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
        propiedades.add("app.demo.datos", () -> "false");
        propiedades.add("app.auth-url", () -> "http://127.0.0.1:1");
        //  El JwtConfig de produccion exige una URL valida aunque la prueba
        //  inyecte su propio decodificador local: nunca se consulta.
        propiedades.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://127.0.0.1:1/.well-known/jwks.json");
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected JwtTestIssuer issuer;
    @Autowired protected ObjectMapper json;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected Flyway flyway;
    @Autowired protected CarteraIntakeService ingesta;
    @Autowired protected OrganizationRepository organizaciones;
    @Autowired protected DebtRepository deudas;
    @Autowired protected InstallmentRepository cuotas;
    @Autowired protected DebtEventRepository eventos;
    @Autowired protected CampaignRepository campanas;
    @Autowired protected BatchRepository lotes;

    /** Sufijo unico por prueba para no chocar con lotes de otras ejecuciones. */
    protected final String sufijo = UUID.randomUUID().toString().substring(0, 8);

    @BeforeEach
    void prepararEmpresa() {
        organizacion();
    }

    protected Organization organizacion() {
        return organizaciones.findByRut(RUT_ACREEDOR).orElseGet(() -> {
            Organization empresa = new Organization();
            empresa.setRut(RUT_ACREEDOR);
            empresa.setLegalName("Patrimonio Inmuebles SpA");
            empresa.setTradeName("Patrimonio Inmuebles");
            empresa.setKind(Organization.Kind.creditor);
            return organizaciones.save(empresa);
        });
    }

    protected Debt crearDeuda(String idExterno, String rutDeudor, String nombre, String correo, long monto) {
        Organization empresa = organizacion();
        ObjectNode cartera = json.createObjectNode();
        cartera.put("version", "1.0");
        ObjectNode lote = cartera.putObject("lote");
        lote.put("id_externo", "LOTE-" + idExterno + "-" + sufijo);
        lote.put("fecha_corte", LocalDate.now().toString());
        lote.putObject("acreedor").put("rut", RUT_ACREEDOR);

        ObjectNode deuda = cartera.putArray("deudas").addObject();
        deuda.put("id_externo", idExterno);
        deuda.put("moneda", "CLP");
        deuda.put("concepto", "Arriendo mensual");
        ObjectNode deudor = deuda.putObject("deudor");
        deudor.put("rut", rutDeudor);
        deudor.put("tipo", "persona");
        deudor.put("nombre", nombre);
        deudor.put("correo", correo);
        ObjectNode cargo = deuda.putArray("cargos").addObject();
        cargo.put("concepto", "Arriendo");
        cargo.put("periodo", LocalDate.now().minusMonths(2).toString().substring(0, 7));
        cargo.put("monto", monto);
        cargo.put("fecha_vencimiento", LocalDate.now().minusDays(31).toString());

        var respuesta = ingesta.recibir(empresa, cartera, Batch.Source.file);
        if (respuesta.aceptadas() != 1) {
            throw new IllegalStateException("La cartera de prueba no se acepto: " + respuesta.resultados());
        }
        return deudas.findByCreditorAndExternalId(empresa, idExterno).orElseThrow();
    }

    /** Deja la deuda pagada por completo: el certificado solo se emite en ese estado. */
    protected Debt marcarPagada(Debt deuda) {
        deuda.setStatus(Debt.Status.paid);
        deudas.save(deuda);
        for (Installment cuota : cuotas.findByDebtOrderByNumberAsc(deuda)) {
            cuota.setStatus(Installment.Status.paid);
            cuota.setPaidAt(Instant.now());
            cuotas.save(cuota);
        }
        return deuda;
    }

    protected DebtEvent registrarPago(Debt deuda, String monto) {
        return eventos.save(DebtEvent.de(deuda, DebtEvent.Type.payment_applied, DebtEvent.Actor.debtor)
                .conMonto(new BigDecimal(monto), deuda.getCurrency())
                .conReferencia("webpay:wp-" + deuda.getExternalId()));
    }

    protected String tokenDeudor(String rut) {
        return issuer.issue(rut, rut + "@correo.cl", "DEBTOR", "Deudor", rut);
    }

    protected String tokenAcreedor() {
        return issuer.issue("empresa-1", "empresa@correo.cl", "CREDITOR", "Patrimonio", RUT_ACREEDOR);
    }
}
