package com.tbridge.debt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.tbridge.debt.dto.response.CarteraResponse;
import com.tbridge.debt.dto.response.CarteraResponse.ResultadoDeuda;
import com.tbridge.debt.dto.response.RevisionResponse;
import com.tbridge.debt.exception.CarteraInvalida;
import com.tbridge.debt.model.Batch;
import com.tbridge.debt.model.Campaign;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.CampaignRepository;
import com.tbridge.debt.repository.MandateRepository;
import com.tbridge.debt.security.JwtPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** La carga desde el portal: siempre en una campana, y con una revision que no guarda nada. */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CargaServiceTest {

    @Mock private CarteraIntakeService ingesta;
    @Mock private DebtService debts;
    @Mock private MandateRepository mandatos;
    @Mock private CampaignRepository campanas;
    @Mock private PlatformTransactionManager transacciones;

    private final JwtPrincipal empresa = new JwtPrincipal("1", "persona@empresa.cl", "CREDITOR", "Persona", "76418902-7");
    private final SimpleTransactionStatus transaccion = new SimpleTransactionStatus();
    private CargaService servicio;
    private Organization patrimonio;

    /** Dos deudas en pesos (una de dos cargos) y una en UF, con coma decimal. */
    private static final byte[] PLANILLA = String.join("\n",
            "deuda_id;accion;motivo_retiro;deudor_rut;deudor_tipo;deudor_nombre;deudor_correo;deudor_telefono;"
                    + "moneda;concepto;referencias;cargo_concepto;cargo_periodo;cargo_monto;cargo_vencimiento",
            "CTR-1;registrar;;16482337-7;persona;Felipe Rojas;felipe@example.com;;CLP;Arriendo;;Agosto;2026-08;520000;2026-08-05",
            "CTR-1;registrar;;16482337-7;persona;Felipe Rojas;felipe@example.com;;CLP;Arriendo;;Septiembre;2026-09;520000;2026-09-05",
            "CTR-2;registrar;;15227640-0;persona;Tomas Fuentes;tomas@example.com;;CLP;Arriendo;;Septiembre;2026-09;680000;2026-09-05",
            "CTR-3;registrar;;76991245-2;empresa;Comercial Nandu;nandu@example.com;;UF;Local;;Julio;2026-07;38,5;2026-07-05")
            .getBytes(StandardCharsets.UTF_8);

    @BeforeEach
    void preparar() {
        patrimonio = new Organization();
        patrimonio.setRut("76418902-7");
        patrimonio.setKind(Organization.Kind.creditor);
        when(debts.organizacionDe(empresa)).thenReturn(patrimonio);
        when(transacciones.getTransaction(any())).thenReturn(transaccion);
        servicio = new CargaService(ingesta, debts, mandatos, campanas, transacciones);
    }

    private static CarteraResponse respuesta(ResultadoDeuda... resultados) {
        return new CarteraResponse("CARGA-1", false, resultados.length,
                (int) List.of(resultados).stream().filter(ResultadoDeuda::aceptada).count(),
                (int) List.of(resultados).stream().filter(r -> !r.aceptada()).count(), List.of(resultados), List.of());
    }

    @Test
    void sin_campana_no_entra_nada() {
        for (String sinCampana : new String[]{null, "  "}) {
            CarteraInvalida fallo = assertThrows(CarteraInvalida.class,
                    () -> servicio.subir(empresa, PLANILLA, null, null, "76418902-7", sinCampana));

            assertEquals("sin_campana", fallo.getCodigo());
            assertEquals(400, fallo.getStatus());
        }
        verify(ingesta, never()).recibir(any(), any(), any());
    }

    @Test
    void en_una_campana_terminada_no_se_carga() {
        Campaign terminada = new Campaign();
        terminada.setName("Arriendos agosto");
        terminada.setStatus(Campaign.Status.finished);
        when(campanas.findByAgencyAndExternalId(patrimonio, "CMP-1")).thenReturn(Optional.of(terminada));

        CarteraInvalida fallo = assertThrows(CarteraInvalida.class,
                () -> servicio.subir(empresa, PLANILLA, null, null, "76418902-7", "CMP-1"));

        assertEquals("campana_terminada", fallo.getCodigo());
        assertEquals(409, fallo.getStatus());
        assertTrue(fallo.getMessage().contains("Arriendos agosto"), fallo.getMessage());
        verify(ingesta, never()).recibir(any(), any(), any());
    }

    @Test
    void la_planilla_entra_en_su_campana_con_el_lote_y_el_corte_armados_solos() {
        servicio.subir(empresa, PLANILLA, null, null, "76418902-7", " CMP-1 ");

        ArgumentCaptor<JsonNode> cartera = ArgumentCaptor.forClass(JsonNode.class);
        verify(ingesta).recibir(eq(patrimonio), cartera.capture(), eq(Batch.Source.file));
        JsonNode lote = cartera.getValue().get("lote");
        //  La empresa cobra su propia cartera: la campana va en el lote, sin mandato.
        assertEquals("CMP-1", lote.get("campana_id_externo").asText());
        assertTrue(lote.get("id_externo").asText().startsWith("CARGA-"), lote.toString());
        assertEquals(LocalDate.now(ZoneId.of("America/Santiago")).toString(), lote.get("fecha_corte").asText());
        assertEquals(3, cartera.getValue().get("deudas").size());
    }

    @Test
    void con_el_lote_y_el_corte_que_manda_el_portal_se_usan_esos() {
        servicio.subir(empresa, PLANILLA, "CARGA-20261015-101500", "2026-10-01", "76418902-7", "CMP-1");

        ArgumentCaptor<JsonNode> cartera = ArgumentCaptor.forClass(JsonNode.class);
        verify(ingesta).recibir(eq(patrimonio), cartera.capture(), eq(Batch.Source.file));
        assertEquals("CARGA-20261015-101500", cartera.getValue().at("/lote/id_externo").asText());
        assertEquals("2026-10-01", cartera.getValue().at("/lote/fecha_corte").asText());
    }

    @Test
    void la_revision_se_deshace_siempre_y_dice_cuanto_entraria_por_moneda() {
        CarteraResponse ingestada = respuesta(
                ResultadoDeuda.registrada("CTR-1", "registrada", 71, "31-90"),
                ResultadoDeuda.rechazada("CTR-2", List.of()),
                ResultadoDeuda.registrada("CTR-3", "actualizada", 102, "91-120"));
        when(ingesta.recibir(eq(patrimonio), any(), eq(Batch.Source.file))).thenReturn(ingestada);

        RevisionResponse revision = servicio.revisar(empresa, PLANILLA, null, null, "76418902-7", "CMP-1");

        assertTrue(transaccion.isRollbackOnly(), "la transaccion de la revision nunca se confirma");
        assertSame(ingestada, revision.cartera());
        //  CTR-2 se rechazaria: no suma. CTR-1 suma sus dos cargos.
        assertEquals(List.of(new RevisionResponse.Monto("CLP", 1, new BigDecimal("1040000")),
                new RevisionResponse.Monto("UF", 1, new BigDecimal("38.5"))), revision.entrarian());
    }

    @Test
    void si_la_ingesta_rechaza_el_lote_la_revision_lo_dice_y_tampoco_guarda() {
        when(ingesta.recibir(any(), any(), any())).thenThrow(new CarteraInvalida("campana_desconocida", "No esta"));

        assertThrows(CarteraInvalida.class,
                () -> servicio.revisar(empresa, PLANILLA, null, null, "76418902-7", "CMP-9"));

        assertTrue(transaccion.isRollbackOnly());
        verify(transacciones).rollback(transaccion);
    }

    @Test
    void sin_campana_tampoco_se_revisa() {
        CarteraInvalida fallo = assertThrows(CarteraInvalida.class,
                () -> servicio.revisar(empresa, PLANILLA, null, null, "76418902-7", null));

        assertEquals("sin_campana", fallo.getCodigo());
        verify(ingesta, never()).recibir(any(), any(), any());
        assertTrue(transaccion.isRollbackOnly());
    }
}
