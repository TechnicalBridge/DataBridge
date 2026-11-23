package com.tbridge.debt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.tbridge.debt.config.SecurityConfig;
import com.tbridge.debt.controller.CargaController;
import com.tbridge.debt.dto.response.CarteraResponse;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.CampaignRepository;
import com.tbridge.debt.repository.MandateRepository;
import com.tbridge.debt.security.JwtTestConfig;
import com.tbridge.debt.security.JwtTestIssuer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Controlador, permisos y parsers reales; ingesta y transacciones aisladas de bases externas. */
@WebMvcTest(CargaController.class)
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@Import({SecurityConfig.class, JwtTestConfig.class, CargaService.class})
@ActiveProfiles("test")
class CargaFormatoIntegracionTest {
    private static final String SUBIR = "/api/debts/cartera";
    private static final String REVISAR = SUBIR + "/revision";
    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final byte[] CSV = ("deuda_id;accion;motivo_retiro;deudor_rut;deudor_tipo;"
            + "deudor_nombre;deudor_correo;deudor_telefono;moneda;concepto;referencias;"
            + "cargo_concepto;cargo_periodo;cargo_monto;cargo_vencimiento\n"
            + "CTR-1;retirar;pago_directo;;;;;;;;;;;;\n").getBytes(StandardCharsets.UTF_8);

    @Autowired private MockMvc mvc;
    @Autowired private JwtTestIssuer jwt;
    @MockitoBean private CarteraIntakeService ingesta;
    @MockitoBean private DebtService debts;
    @MockitoBean private CampaignRepository campanas;
    @MockitoBean private MandateRepository mandatos;
    @MockitoBean private PlatformTransactionManager transacciones;
    private SimpleTransactionStatus transaccion;

    @BeforeEach
    void preparar() {
        Organization empresa = new Organization();
        empresa.setRut("76418902-7");
        empresa.setKind(Organization.Kind.creditor);
        when(debts.organizacionDe(any())).thenReturn(empresa);
        transaccion = new SimpleTransactionStatus();
        when(transacciones.getTransaction(any())).thenReturn(transaccion);
    }

    private String sesion(String rol) {
        return "Bearer " + jwt.issue("1", "qa@example.com", rol, "QA", "76418902-7");
    }

    static Stream<Arguments> invalidos() throws Exception {
        byte[] zip;
        try (ByteArrayOutputStream salida = new ByteArrayOutputStream();
             ZipOutputStream archivo = new ZipOutputStream(salida)) {
            archivo.putNextEntry(new ZipEntry("nota.txt"));
            archivo.write("No es un libro de Excel".getBytes(StandardCharsets.UTF_8));
            archivo.closeEntry();
            archivo.finish();
            zip = salida.toByteArray();
        }
        List<MockMultipartFile> archivos = List.of(
                new MockMultipartFile("archivo", "vacio.csv", "text/csv", new byte[0]),
                new MockMultipartFile("archivo", "cartera.txt", "text/plain", CSV),
                new MockMultipartFile("archivo", "cartera.csv.exe", "application/octet-stream", CSV),
                new MockMultipartFile("archivo", "cartera.exe.csv", "text/csv", new byte[]{0, 1, 2, 3}),
                new MockMultipartFile("archivo", "cartera.csv", "text/csv", "<html>sin columnas</html>".getBytes(StandardCharsets.UTF_8)),
                new MockMultipartFile("archivo", "cartera.csv", "image/png", CSV),
                new MockMultipartFile("archivo", "cartera.xlsx", XLSX, CSV),
                new MockMultipartFile("archivo", "cartera.xlsx", "application/octet-stream", zip),
                new MockMultipartFile("archivo", "cartera.xlsx", XLSX, zip),
                new MockMultipartFile("archivo", "cartera.csv", "application/octet-stream", zip));
        return Stream.of(SUBIR, REVISAR).flatMap(ruta -> archivos.stream().map(a -> Arguments.of(ruta, a)));
    }

    @ParameterizedTest
    @MethodSource("invalidos")
    void rechaza_sin_alcanzar_la_ingesta(String ruta, MockMultipartFile archivo) throws Exception {
        mvc.perform(multipart(ruta).file(archivo).param("acreedor_rut", "76418902-7")
                        .param("campana_id_externo", "CMP-1").header("Authorization", sesion("CREDITOR")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.codigo").isString());
        verifyNoInteractions(ingesta);
    }

    static Stream<Arguments> validos() {
        byte[] excel = new PlanillaDePrueba().deuda("CTR-1", null, "retirar", null, null,
                null, null, null, null, null, "pago_directo").bytes();
        return Stream.of(SUBIR, REVISAR).flatMap(ruta -> Stream.of(
                Arguments.of(ruta, new MockMultipartFile("archivo", "cartera.CSV", "text/csv; charset=utf-8", CSV)),
                Arguments.of(ruta, new MockMultipartFile("archivo", "cartera.respaldo.csv", "application/octet-stream", CSV)),
                Arguments.of(ruta, new MockMultipartFile("archivo", "cartera.xlsx", XLSX, excel)),
                Arguments.of(ruta, new MockMultipartFile("archivo", "cartera.XLSX", "application/octet-stream", excel))));
    }

    @ParameterizedTest
    @MethodSource("validos")
    void conserva_csv_y_xlsx_y_marca_la_revision_para_rollback(String ruta, MockMultipartFile archivo) throws Exception {
        when(ingesta.recibir(any(), any(), any())).thenReturn(new CarteraResponse("QA", false, 1, 1, 0,
                List.of(CarteraResponse.ResultadoDeuda.retirada("CTR-1")), List.of()));
        mvc.perform(multipart(ruta).file(archivo).param("acreedor_rut", "76418902-7")
                        .param("campana_id_externo", "CMP-1").header("Authorization", sesion("CREDITOR")))
                .andExpect(status().isOk());
        ArgumentCaptor<JsonNode> cartera = ArgumentCaptor.forClass(JsonNode.class);
        verify(ingesta).recibir(any(), cartera.capture(), any());
        assertEquals("CTR-1", cartera.getValue().path("deudas").get(0).path("id_externo").asText());
        assertEquals("retirar", cartera.getValue().path("deudas").get(0).path("accion").asText());
        if (REVISAR.equals(ruta)) {
            assertTrue(transaccion.isRollbackOnly());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {SUBIR, REVISAR})
    void deudor_no_llega_a_parsers_ni_ingesta(String ruta) throws Exception {
        mvc.perform(multipart(ruta).file(new MockMultipartFile("archivo", "cartera.csv", "text/csv", CSV))
                        .param("acreedor_rut", "76418902-7").param("campana_id_externo", "CMP-1")
                        .header("Authorization", sesion("DEBTOR")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(debts, ingesta, campanas, mandatos, transacciones);
    }
}
