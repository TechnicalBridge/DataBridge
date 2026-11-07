package com.tbridge.debt.integracion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tbridge.debt.dto.request.CampanaRequest;
import com.tbridge.debt.model.Batch;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.Installment;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.BatchRepository;
import com.tbridge.debt.repository.CampaignRepository;
import com.tbridge.debt.repository.DebtRepository;
import com.tbridge.debt.repository.InstallmentRepository;
import com.tbridge.debt.repository.OrganizationRepository;
import com.tbridge.debt.security.JwtTestConfig;
import com.tbridge.debt.security.JwtTestIssuer;
import com.tbridge.debt.security.JwtTestTokens;
import com.tbridge.debt.service.CarteraIntakeService;
import com.tbridge.debt.service.MandatoService;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.annotation.Rollback;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Pruebas de integracion de ms-debt contra una base de MySQL real.
 *
 * <p>El servicio se levanta completo contra el MySQL del entorno de desarrollo
 * (puerto 3309, levantado por docker-compose del repositorio), Flyway ejecuta
 * las migraciones y los controladores responden contra la base de verdad.
 *
 * <p>La clase lleva {@link Testcontainers(disabledWithoutDocker = true)} como
 * exige el plan de pruebas: en entornos con Docker y Testcontainers funcionando
 * se puede apuntar el {@link DynamicPropertySource} a un contenedor efimero
 * MySQL; en este entorno se usa el MySQL ya disponible del compose.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(JwtTestConfig.class)
@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Transactional
@Rollback(true)
class IntegracionControladoresTest {

    private static final String RUT_ACREEDOR = "76418902-7";
    private static final String RUT_DEUDOR = "18905214-6";
    private static final String RUT_OTRO_DEUDOR = "16482337-7";
    private static final String CLAVE_INTERNA = "clave-interna-de-prueba";

    @DynamicPropertySource
    static void baseDeDatos(DynamicPropertyRegistry propiedades) {
        //  MySQL real del entorno (docker-compose del repo, puerto 3309 -> 3306).
        //  Las credenciales vienen del .env del repositorio.
        propiedades.add("spring.datasource.url",
                () -> "jdbc:mysql://127.0.0.1:3309/tb_debt?useUnicode=true&characterEncoding=utf8&serverTimezone=America/Santiago");
        propiedades.add("spring.datasource.username", () -> "tbridge");
        propiedades.add("spring.datasource.password",
                () -> "WX6ePjZKd-MS4Y3AUdNJmiaTzZUAypRnkVmzsKU3bTJkR_qvJ6yNwq84DTVnvdqr");
        propiedades.add("spring.flyway.enabled", () -> "true");
        propiedades.add("app.demo.datos", () -> "false");
        propiedades.add("app.auth-url", () -> "http://127.0.0.1:1");
    }

    @Autowired MockMvc mvc;
    @Autowired JwtTestIssuer issuer;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired Flyway flyway;
    @Autowired CarteraIntakeService ingesta;
    @Autowired MandatoService mandatos;
    @Autowired OrganizationRepository organizaciones;
    @Autowired DebtRepository deudas;
    @Autowired InstallmentRepository cuotas;
    @Autowired CampaignRepository campanas;
    @Autowired BatchRepository lotes;

    /** Sufijo unico por ejecucion para no chocar con lotes de pruebas previas. */
    final String sufijo = String.valueOf(System.currentTimeMillis());

    @BeforeEach
    void prepararEmpresa() {
        organizacion();
    }

    // ------------------------------------------------------------------
    //  Migraciones
    // ------------------------------------------------------------------

    @Test
    @Order(1)
    void las_migraciones_estan_aplicadas_en_mysql_de_verdad() {
        assertThat(flyway.info().applied()).hasSize(13);
        assertThat(flyway.info().pending()).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class)).isEqualTo(13);
        assertThat(jdbc.queryForObject(
                "SELECT version FROM flyway_schema_history ORDER BY installed_rank DESC LIMIT 1", String.class))
                .isEqualTo("13");
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name IN ('organizations','debtors','debts','debt_charges','installments',
                                     'campaigns','mandates','batches','debt_events','contacts','outbox')
                """, Integer.class)).isEqualTo(11);
    }

    // ------------------------------------------------------------------
    //  DebtController: lista, detalle, simulacion y repactacion
    // ------------------------------------------------------------------

    @Test
    @Order(2)
    void lista_detalle_simula_y_repacta_con_la_base_de_verdad() throws Exception {
        Debt deuda = crearDeuda("IT-DEBT-1", RUT_DEUDOR, "Valentina Soto", "valentina.soto@correo.cl", 410000);
        String deudor = tokenDeudor(RUT_DEUDOR);

        mvc.perform(get("/api/debts").header("Authorization", "Bearer " + deudor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.debts[*].id").value(hasItem(deuda.getId().intValue())));

        mvc.perform(get("/api/debts/" + deuda.getId()).header("Authorization", "Bearer " + deudor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(deuda.getId()))
                .andExpect(jsonPath("$.cargos.length()").value(1))
                .andExpect(jsonPath("$.cuotas.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));

        mvc.perform(get("/api/debts/" + deuda.getId() + "/simulate?months=6")
                        .header("Authorization", "Bearer " + deudor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.plan.months").value(6))
                .andExpect(jsonPath("$.plan.cuotas.length()").value(6));

        mvc.perform(post("/api/debts/" + deuda.getId() + "/repact")
                        .header("Authorization", "Bearer " + deudor)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"months\":6}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("repacted"));

        assertThat(cuotas.findByDebtOrderByNumberAsc(deuda)).isNotEmpty();
        assertThat(cuotas.findByDebtOrderByNumberAsc(deuda).stream()
                .filter(c -> c.getStatus() == Installment.Status.pending)).isNotEmpty();
    }

    // ------------------------------------------------------------------
    //  Seguridad: sin sesion, sin permiso, token sin firma y deuda ajena
    // ------------------------------------------------------------------

    @Test
    @Order(3)
    void sin_sesion_o_sin_permiso_no_entra() throws Exception {
        mvc.perform(get("/api/debts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/debts").header("Authorization", "Bearer " + tokenAdmin()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/debt/empresas").header("Authorization", "Bearer " + tokenAdmin()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/debt/empresas").header("Authorization", "Bearer " + tokenAcreedor()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/debt/empresas").header("Authorization", "Bearer " + tokenDeudor(RUT_DEUDOR)))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(4)
    void un_token_sin_firma_o_con_otra_llave_es_401() throws Exception {
        String sinFirma = JwtTestTokens.token(JwtTestTokens.claims(), JwtTestTokens.PAIR,
                JwtTestTokens.publicKey(JwtTestTokens.PAIR).getKeyID(), "none");
        mvc.perform(get("/api/debts").header("Authorization", "Bearer " + sinFirma))
                .andExpect(status().isUnauthorized());

        var otraLlave = JwtTestTokens.pair("secp256r1");
        String firmadoConOtra = JwtTestTokens.token(JwtTestTokens.claims(), otraLlave,
                JwtTestTokens.publicKey(JwtTestTokens.PAIR).getKeyID(), "ES256");
        mvc.perform(get("/api/debts").header("Authorization", "Bearer " + firmadoConOtra))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(5)
    void la_deuda_de_otro_no_se_ve_ni_se_repacta() throws Exception {
        Debt deuda = crearDeuda("IT-DEBT-AJENA", RUT_DEUDOR, "Valentina Soto", "valentina.soto@correo.cl", 410000);
        String otro = tokenDeudor(RUT_OTRO_DEUDOR);

        mvc.perform(get("/api/debts/" + deuda.getId()).header("Authorization", "Bearer " + otro))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/debts/" + deuda.getId() + "/simulate?months=6")
                        .header("Authorization", "Bearer " + otro))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/debts/" + deuda.getId() + "/repact")
                        .header("Authorization", "Bearer " + otro)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"months\":6}"))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    //  CargaController: la revision no guarda y la carga si
    // ------------------------------------------------------------------

    @Test
    @Order(6)
    void la_revision_no_guarda_nada_y_la_carga_si() throws Exception {
        crearCampana("IT-CAMPANA");
        String acreditado = tokenAcreedor();
        long antes = deudas.count();
        String csv = csvDeCarga("IT-CARGA-NUEVA-" + sufijo, RUT_DEUDOR);

        MockMultipartFile revision = archivo("revision.csv", csv);
        mvc.perform(multipart("/api/debts/cartera/revision")
                        .file(revision)
                        .param("acreedor_rut", RUT_ACREEDOR)
                        .param("campana_id_externo", "IT-CAMPANA")
                        .param("lote_id_externo", "IT-LOTE-REVISION-" + sufijo)
                        .header("Authorization", "Bearer " + acreditado))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.cartera.aceptadas").value(1))
                .andExpect(jsonPath("$.entrarian[0].moneda").value("CLP"))
                .andExpect(jsonPath("$.entrarian[0].deudas").value(1));

        assertThat(deudas.count()).isEqualTo(antes);
        assertThat(lotes.findBySenderAndExternalId(organizacion(), "IT-LOTE-REVISION-" + sufijo)).isEmpty();

        MockMultipartFile carga = archivo("carga.csv", csv);
        mvc.perform(multipart("/api/debts/cartera")
                        .file(carga)
                        .param("acreedor_rut", RUT_ACREEDOR)
                        .param("campana_id_externo", "IT-CAMPANA")
                        .param("lote_id_externo", "IT-LOTE-CARGA-" + sufijo)
                        .header("Authorization", "Bearer " + acreditado))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.aceptadas").value(1))
                .andExpect(jsonPath("$.repetido").value(false));

        assertThat(deudas.count()).isGreaterThan(antes);
        assertThat(deudas.findByCreditorAndExternalId(organizacion(), "IT-CARGA-NUEVA-" + sufijo)).isPresent();

        MockMultipartFile repetida = archivo("carga.csv", csv);
        mvc.perform(multipart("/api/debts/cartera")
                        .file(repetida)
                        .param("acreedor_rut", RUT_ACREEDOR)
                        .param("campana_id_externo", "IT-CAMPANA")
                        .param("lote_id_externo", "IT-LOTE-CARGA-" + sufijo)
                        .header("Authorization", "Bearer " + acreditado))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.repetido").value(true));

        assertThat(deudas.count()).isEqualTo(antes + 1);

        MockMultipartFile deDeudor = archivo("carga.csv", csv);
        mvc.perform(multipart("/api/debts/cartera")
                        .file(deDeudor)
                        .param("acreedor_rut", RUT_ACREEDOR)
                        .param("campana_id_externo", "IT-CAMPANA")
                        .header("Authorization", "Bearer " + tokenDeudor(RUT_DEUDOR)))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------
    //  API apagada por omision y clave interna
    // ------------------------------------------------------------------

    @Test
    @Order(7)
    void la_api_de_integracion_esta_apagada_por_omision() throws Exception {
        mvc.perform(get("/api/v1/cuenta").header("Authorization", "Bearer tbk_cualquiera"))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/claves").header("Authorization", "Bearer " + tokenAcreedor()))
                .andExpect(status().isNotFound());
    }

    @Test
    @Order(8)
    void la_api_interna_exige_su_clave() throws Exception {
        Debt deuda = crearDeuda("IT-DEBT-INTERNA", RUT_DEUDOR, "Valentina Soto",
                "valentina.soto@correo.cl", 410000);

        mvc.perform(get("/internal/debts/" + deuda.getId())).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/debts/" + deuda.getId()).header("X-Internal-Key", "otra"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/debts/" + deuda.getId()).header("X-Internal-Key", CLAVE_INTERNA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.debtId").value(deuda.getId()));
    }

    // ------------------------------------------------------------------
    //  Los demas controladores
    // ------------------------------------------------------------------

    @Test
    @Order(9)
    void campanas_mandatos_cuotas_historial_y_mis_datos() throws Exception {
        Debt deuda = crearDeuda("IT-DEBT-RESTO", RUT_DEUDOR, "Valentina Soto",
                "valentina.soto@correo.cl", 410000);
        String acreditado = tokenAcreedor();
        String deudor = tokenDeudor(RUT_DEUDOR);

        mvc.perform(get("/api/debts/campanas").header("Authorization", "Bearer " + acreditado))
                .andExpect(status().isOk());
        mvc.perform(get("/api/debts/mandatos").header("Authorization", "Bearer " + acreditado))
                .andExpect(status().isOk());
        mvc.perform(get("/api/debts/en-riesgo").header("Authorization", "Bearer " + acreditado))
                .andExpect(status().isOk());
        mvc.perform(get("/api/analytics/summary").header("Authorization", "Bearer " + acreditado))
                .andExpect(status().isOk());

        mvc.perform(get("/api/debts/vencimientos").header("Authorization", "Bearer " + deudor))
                .andExpect(status().isOk());
        mvc.perform(get("/api/debts/pagos").header("Authorization", "Bearer " + deudor))
                .andExpect(status().isOk());
        mvc.perform(get("/api/debts/mis-datos").header("Authorization", "Bearer " + deudor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rut").value(RUT_DEUDOR));

        mvc.perform(get("/api/debts/vencimientos").header("Authorization", "Bearer " + acreditado))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/debts/en-riesgo").header("Authorization", "Bearer " + deudor))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/debts/campanas").header("Authorization", "Bearer " + deudor))
                .andExpect(status().isForbidden());

        assertThat(deudas.findById(deuda.getId())).isPresent();
    }

    // ------------------------------------------------------------------
    //  Utilidades
    // ------------------------------------------------------------------

    private Organization organizacion() {
        return organizaciones.findByRut(RUT_ACREEDOR).orElseGet(() -> {
            Organization empresa = new Organization();
            empresa.setRut(RUT_ACREEDOR);
            empresa.setLegalName("Patrimonio Inmuebles SpA");
            empresa.setTradeName("Patrimonio Inmuebles");
            empresa.setKind(Organization.Kind.creditor);
            return organizaciones.save(empresa);
        });
    }

    private void crearCampana(String idExterno) {
        Organization empresa = organizacion();
        if (campanas.findByAgencyAndExternalId(empresa, idExterno).isPresent()) {
            return;
        }
        mandatos.registrarCampana(empresa, new CampanaRequest(idExterno, RUT_ACREEDOR,
                "Campana de integracion", LocalDate.now().toString(), null,
                json.createArrayNode().add("correo"), 3,
                json.createArrayNode().add(1).add(4).add(11), "en_curso"));
    }

    private Debt crearDeuda(String idExterno, String rutDeudor, String nombre, String correo, long monto) {
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
        assertThat(respuesta.aceptadas()).isEqualTo(1);
        return deudas.findByCreditorAndExternalId(empresa, idExterno).orElseThrow();
    }

    private String csvDeCarga(String idDeuda, String rutDeudor) {
        String vencimiento = LocalDate.now().minusDays(31).toString();
        String periodo = LocalDate.now().minusMonths(2).toString().substring(0, 7);
        return """
                deuda_id;accion;motivo_retiro;deudor_rut;deudor_tipo;deudor_nombre;deudor_correo;deudor_telefono;moneda;concepto;referencias;tasa_interes_mensual;cargo_concepto;cargo_periodo;cargo_monto;cargo_vencimiento
                %s;registrar;;%s;persona;Valentina Soto;valentina.soto@correo.cl;;CLP;Arriendo;contrato=%s;;Arriendo;%s;410000;%s
                """.formatted(idDeuda, rutDeudor, idDeuda, periodo, vencimiento);
    }

    private MockMultipartFile archivo(String nombre, String contenido) {
        return new MockMultipartFile("archivo", nombre, "text/csv",
                contenido.getBytes(StandardCharsets.UTF_8));
    }

    private String tokenDeudor(String rut) {
        return issuer.issue(rut, "deudor@correo.cl", "DEBTOR", "Deudor", rut);
    }

    private String tokenAcreedor() {
        return issuer.issue("empresa-1", "empresa@correo.cl", "CREDITOR", "Patrimonio", RUT_ACREEDOR);
    }

    private String tokenAdmin() {
        return issuer.issue("admin-1", "admin@correo.cl", "ADMIN", "Admin", null);
    }
}
