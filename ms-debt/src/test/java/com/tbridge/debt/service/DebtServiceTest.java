package com.tbridge.debt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.debt.events.PagoConfirmado;
import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.security.JwtPrincipal;
import com.tbridge.debt.dto.evento.PagoConfirmadoDatos;
import com.tbridge.debt.dto.evento.RepactacionAceptadaDatos;
import com.tbridge.debt.dto.response.DebtDetailResponse;
import com.tbridge.debt.dto.response.DebtSnapshotResponse;
import com.tbridge.debt.dto.response.RepactPlan;
import com.tbridge.debt.dto.response.DebtSummaryResponse;
import com.tbridge.debt.model.Batch;
import com.tbridge.debt.model.Campaign;
import com.tbridge.debt.model.Contact;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.DebtCharge;
import com.tbridge.debt.model.DebtEvent;
import com.tbridge.debt.model.Debtor;
import com.tbridge.debt.model.DetallePago;
import com.tbridge.debt.model.Installment;
import com.tbridge.debt.model.Mandate;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.model.Repactation;
import com.tbridge.debt.repository.ContactRepository;
import com.tbridge.debt.repository.DebtChargeRepository;
import com.tbridge.debt.repository.DebtEventRepository;
import com.tbridge.debt.repository.DebtRepository;
import com.tbridge.debt.repository.DebtorRepository;
import com.tbridge.debt.repository.InstallmentRepository;
import com.tbridge.debt.repository.OrganizationRepository;
import com.tbridge.debt.repository.RepactationRepository;
import com.tbridge.debt.repository.UltimoContacto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Las reglas de las deudas: cada quien ve lo suyo, el saldo sale de las
 * cuotas, y un pago avisado dos veces se abona una.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DebtServiceTest {

    @Mock private DebtRepository debts;
    @Mock private DebtorRepository debtors;
    @Mock private OrganizationRepository organizations;
    @Mock private DebtChargeRepository charges;
    @Mock private InstallmentRepository installments;
    @Mock private RepactationRepository repactations;
    @Mock private DebtEventRepository events;
    @Mock private ContactRepository contactos;
    @Mock private EventosService eventos;
    @Mock private DescuentoService descuentos;
    @Spy private RepactationService repactation = new RepactationService();
    @Spy private ObjectMapper json = new ObjectMapper();

    @InjectMocks
    private DebtService servicio;

    private Organization patrimonio;
    private Organization apofyx;
    private Debtor valentina;
    private Debt deuda;
    private final List<Installment> cuotas = new ArrayList<>();
    private final List<DebtEvent> historia = new ArrayList<>();

    private static Organization organizacion(long id, String rut, String nombre) {
        Organization org = new Organization();
        org.setId(id);
        org.setRut(rut);
        org.setTradeName(nombre);
        return org;
    }

    @BeforeEach
    void preparar() {
        patrimonio = organizacion(1L, "76418902-7", "Patrimonio Inmuebles");
        apofyx = organizacion(2L, "77305118-6", "APOFYX");
        valentina = new Debtor();
        valentina.setId(10L);
        valentina.setRut("18905214-6");
        valentina.setFullName("Valentina Soto Pizarro");

        Batch lote = new Batch();
        lote.setSender(apofyx);
        lote.setExternalId("APX-2026-09-19-004");
        deuda = new Debt();
        deuda.setId(3L);
        deuda.setCreditor(patrimonio);
        deuda.setDebtor(valentina);
        deuda.setLastBatch(lote);
        deuda.setExternalId("CTR-2026-031");
        deuda.setConcept("Arriendo mensual");
        deuda.setCurrency(Debt.Currency.CLP);
        deuda.setOriginalAmount(new BigDecimal("410000"));

        Installment unica = new Installment();
        unica.setId(12L);
        unica.setDebt(deuda);
        unica.setNumber((short) 1);
        unica.setDueDate(LocalDate.of(2026, 9, 5));
        unica.setAmount(new BigDecimal("410000"));
        cuotas.add(unica);

        when(debts.findById(3L)).thenReturn(Optional.of(deuda));
        when(installments.findById(any())).thenAnswer(llamada -> cuotas.stream()
                .filter(c -> c.getId().equals(llamada.getArgument(0))).findFirst());
        when(installments.findByDebtOrderByNumberAsc(deuda)).thenReturn(cuotas);
        when(installments.findByDebtAndStatus(eq(deuda), any())).thenAnswer(llamada -> cuotas.stream()
                .filter(c -> c.getStatus() == llamada.getArgument(1)).toList());
        when(events.findByDebtOrderByOccurredAtAsc(deuda)).thenReturn(historia);
        when(events.save(any())).thenAnswer(llamada -> {
            historia.add(llamada.getArgument(0));
            return llamada.getArgument(0);
        });
        when(debtors.findByRut("18905214-6")).thenReturn(Optional.of(valentina));
        when(organizations.findByRut("77305118-6")).thenReturn(Optional.of(apofyx));
        when(organizations.findByRut("76418902-7")).thenReturn(Optional.of(patrimonio));
    }

    private static JwtPrincipal deudor(String rut) {
        return new JwtPrincipal(rut, null, "DEBTOR", null, rut);
    }

    private static JwtPrincipal empresa(String rut) {
        return new JwtPrincipal("1", "x@empresa.cl", "CREDITOR", "Alguien", rut);
    }

    @Test
    void el_deudor_ve_su_deuda_con_el_saldo_de_las_cuotas() {
        when(debts.findByDebtorOrderByUpdatedAtDesc(valentina)).thenReturn(List.of(deuda));

        List<DebtSummaryResponse> suyas = servicio.listFor(deudor("18905214-6"));

        assertEquals(1, suyas.size());
        assertEquals(new BigDecimal("410000"), suyas.getFirst().saldo());
        assertEquals(BigDecimal.ZERO, suyas.getFirst().pagado());
        assertEquals(0, suyas.getFirst().cuotasPagadas());
        assertEquals(1, suyas.getFirst().cuotasTotales());
        assertEquals(false, suyas.getFirst().conConvenio());
    }

    /** Pasa la deuda a un convenio de tres cuotas de 140.000, la 12 anulada. */
    private void enConvenioDeTres() {
        cuotas.getFirst().setStatus(Installment.Status.void_);
        Repactation convenio = new Repactation();
        for (long id = 13; id <= 15; id++) {
            Installment cuota = new Installment();
            cuota.setId(id);
            cuota.setDebt(deuda);
            cuota.setRepactation(convenio);
            cuota.setNumber((short) (id - 11));
            cuota.setDueDate(LocalDate.of(2026, 10, 5).plusMonths(id - 13));
            cuota.setAmount(new BigDecimal("140000"));
            cuotas.add(cuota);
        }
        deuda.setStatus(Debt.Status.repacted);
    }

    @Test
    void el_avance_cuenta_las_cuotas_vigentes_y_no_las_anuladas() {
        enConvenioDeTres();
        cuotas.get(1).setStatus(Installment.Status.paid);
        when(debts.findByDebtorOrderByUpdatedAtDesc(valentina)).thenReturn(List.of(deuda));

        DebtSummaryResponse resumen = servicio.listFor(deudor("18905214-6")).getFirst();

        assertEquals(1, resumen.cuotasPagadas());
        assertEquals(3, resumen.cuotasTotales());
        assertEquals(new BigDecimal("280000"), resumen.saldo());
        assertEquals(true, resumen.conConvenio());
    }

    @Test
    void varias_cuotas_se_cobran_juntas_si_son_las_que_vencen_primero() {
        enConvenioDeTres();

        DebtSnapshotResponse snapshot = servicio.snapshotInterno(3L, List.of(14L, 13L));

        assertEquals(new BigDecimal("280000"), snapshot.amount());
        //  Con varias no va ninguna: el pago se imputa de la mas antigua a la mas nueva.
        assertEquals(null, snapshot.installmentId());
        assertEquals(List.of(13L, 14L), snapshot.installmentIds(), "las que cubre, de la que vence primero");
    }

    @Test
    void no_se_salta_una_cuota_vieja_para_pagar_una_nueva() {
        enConvenioDeTres();

        ApiException error = assertThrows(ApiException.class, () -> servicio.snapshotInterno(3L, List.of(14L)));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertEquals("Las cuotas se pagan en orden, desde la que vence primero", error.getMessage());
    }

    @Test
    void una_cuota_anulada_o_repetida_no_se_cobra() {
        enConvenioDeTres();

        assertEquals(HttpStatus.CONFLICT,
                assertThrows(ApiException.class, () -> servicio.snapshotInterno(3L, List.of(12L))).getStatus());
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ApiException.class, () -> servicio.snapshotInterno(3L, List.of(13L, 13L))).getStatus());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ApiException.class, () -> servicio.snapshotInterno(3L, List.of(99L))).getStatus());
    }

    @Test
    void pagar_el_saldo_de_un_convenio_salda_todas_las_cuotas() {
        //  Antes el cobro del saldo llevaba la id de la primera cuota, y al
        //  confirmarse solo esa quedaba pagada.
        enConvenioDeTres();
        DebtSnapshotResponse snapshot = servicio.snapshotInterno(3L, null);
        assertEquals(new BigDecimal("420000"), snapshot.amount());
        assertEquals(null, snapshot.installmentId());
        assertEquals(List.of(13L, 14L, 15L), snapshot.installmentIds(), "el saldo cubre todas, sin la anulada");

        servicio.onPagoConfirmado(new PagoConfirmado(PagoConfirmado.TIPO, 42L, 3L, snapshot.installmentId(),
                "18905214-6", "76418902-7", snapshot.amount(), "CLP", 420000L, null, "webpay", "wp-convenio",
                Instant.now()));

        assertEquals(Debt.Status.paid, deuda.getStatus());
    }

    @Test
    void el_pago_guarda_que_cuotas_cubrio_por_donde_y_cuando() throws Exception {
        enConvenioDeTres();
        Instant pagadoEn = Instant.parse("2026-09-23T15:30:00Z");

        servicio.onPagoConfirmado(new PagoConfirmado(PagoConfirmado.TIPO, 43L, 3L, null, "18905214-6",
                "76418902-7", new BigDecimal("280000"), "CLP", 280000L, null, "WEBPAY", "wp-dos", pagadoEn));

        DebtEvent aplicado = historia.stream()
                .filter(e -> e.getType() == DebtEvent.Type.payment_applied).findFirst().orElseThrow();
        JsonNode detalle = json.readTree(aplicado.getDetail());
        //  La anulada (la 12) no cuenta: el plan es de tres, y se pagaron la 1 y la 2.
        assertEquals("[1,2]", detalle.get("cuotas").toString());
        assertEquals(3, detalle.get("de").asInt());
        assertEquals("webpay", detalle.get("pasarela").asText());
        assertEquals(43, detalle.get("pago_id").asInt());
        assertEquals(280000, detalle.get("monto_clp").asInt());
        //  Fechado cuando pago el deudor, no cuando llego el aviso.
        assertEquals(pagadoEn, aplicado.getOccurredAt());
    }

    @Test
    void la_comision_conserva_la_empresa_y_campana_del_pago_aunque_recarguen_la_deuda() throws Exception {
        Campaign campana = new Campaign();
        campana.setExternalId("CMP-ORIGINAL");
        campana.setName("Campaña original");
        deuda.setCampaign(campana);
        servicio.onPagoConfirmado(aviso());
        DebtEvent aplicado = historia.stream()
                .filter(e -> e.getType() == DebtEvent.Type.payment_applied).findFirst().orElseThrow();
        DetallePago detalle = json.readValue(aplicado.getDetail(), DetallePago.class);
        assertEquals(apofyx.getRut(), detalle.empresaRut());
        assertEquals(apofyx.getTradeName(), detalle.empresaNombre());
        assertEquals("CMP-ORIGINAL", detalle.campanaId());

        Batch otroLote = new Batch();
        otroLote.setSender(patrimonio);
        deuda.setLastBatch(otroLote);
        deuda.setCampaign(null);
        when(events.entre(any(), any(), any())).thenReturn(List.of(aplicado));
        ComisionService comisiones = new ComisionService(events, servicio, json);
        var original = comisiones.deLaEmpresa(empresa(apofyx.getRut()), YearMonth.of(2026, 9));
        assertEquals(410_000, original.recuperado());
        assertEquals("CMP-ORIGINAL", original.porCampana().getFirst().idExterno());
        assertEquals(0, comisiones.deLaEmpresa(empresa(patrimonio.getRut()), YearMonth.of(2026, 9)).recuperado());
        assertEquals(apofyx.getRut(), comisiones.deTodas(YearMonth.of(2026, 9)).empresas().getFirst().rut());
    }

    /** Un mes que el acreedor informo despues del convenio: una cuota aparte, la que vence primero. */
    private void conUnMesAparte() {
        Installment aparte = new Installment();
        aparte.setId(16L);
        aparte.setDebt(deuda);
        aparte.setNumber((short) 5);
        aparte.setDueDate(LocalDate.of(2026, 10, 1));
        aparte.setAmount(new BigDecimal("410000"));
        cuotas.add(aparte);
    }

    @Test
    void el_mes_aparte_no_cuenta_en_el_avance_del_convenio() {
        enConvenioDeTres();
        cuotas.get(1).setStatus(Installment.Status.paid);
        conUnMesAparte();
        when(debts.findByDebtorOrderByUpdatedAtDesc(valentina)).thenReturn(List.of(deuda));

        DebtSummaryResponse resumen = servicio.listFor(deudor("18905214-6")).getFirst();

        assertEquals(1, resumen.cuotasPagadas());
        assertEquals(3, resumen.cuotasTotales());
        assertEquals(new BigDecimal("690000"), resumen.saldo());
        //  Lo pagado es lo que se pago aca, no el monto original menos el saldo.
        assertEquals(new BigDecimal("140000"), resumen.pagado());
    }

    @Test
    void un_pago_que_cubre_el_mes_aparte_lo_anota_fuera_del_convenio() throws Exception {
        enConvenioDeTres();
        conUnMesAparte();

        //  El mes aparte vence primero: se paga el y la primera del convenio.
        servicio.onPagoConfirmado(new PagoConfirmado(PagoConfirmado.TIPO, 44L, 3L, null, "18905214-6",
                "76418902-7", new BigDecimal("550000"), "CLP", 550000L, null, "webpay", "wp-aparte", Instant.now()));

        JsonNode detalle = json.readTree(historia.stream()
                .filter(e -> e.getType() == DebtEvent.Type.payment_applied).findFirst().orElseThrow().getDetail());
        assertEquals("[1]", detalle.get("cuotas").toString());
        assertEquals(3, detalle.get("de").asInt());
        assertEquals(1, detalle.get("fuera").asInt());
    }

    @Test
    void dos_cuotas_pagadas_juntas_dejan_la_tercera_pendiente() {
        enConvenioDeTres();

        servicio.onPagoConfirmado(new PagoConfirmado(PagoConfirmado.TIPO, 43L, 3L, null, "18905214-6",
                "76418902-7", new BigDecimal("280000"), "CLP", 280000L, null, "webpay", "wp-dos", Instant.now()));

        assertEquals(List.of(Installment.Status.void_, Installment.Status.paid, Installment.Status.paid,
                Installment.Status.pending), cuotas.stream().map(Installment::getStatus).toList());
        assertEquals(Debt.Status.repacted, deuda.getStatus());
    }

    // ------------------------------------------------------------------
    //  Los contactos al deudor
    // ------------------------------------------------------------------

    private static Instant el(String fechaYHora) {
        return java.time.LocalDateTime.parse(fechaYHora).atZone(ZoneId.of("America/Santiago")).toInstant();
    }

    @Test
    void la_empresa_ve_cuando_se_le_escribio_por_ultima_vez_a_cada_deudor() {
        Debt sinInvitar = new Debt();
        sinInvitar.setId(4L);
        sinInvitar.setCreditor(patrimonio);
        sinInvitar.setDebtor(valentina);
        sinInvitar.setLastBatch(deuda.getLastBatch());
        sinInvitar.setCurrency(Debt.Currency.CLP);
        sinInvitar.setOriginalAmount(new BigDecimal("100000"));
        when(debts.carteraDe(apofyx)).thenReturn(List.of(deuda, sinInvitar));
        //  La base responde solo por las deudas a las que se les escribio alguna vez.
        when(contactos.ultimoPorDeuda(List.of(deuda, sinInvitar)))
                .thenReturn(List.of(new UltimoContacto(3L, el("2026-10-06T10:00"))));

        List<DebtSummaryResponse> cartera = servicio.listFor(empresa("77305118-6"));

        assertEquals(el("2026-10-06T10:00"), cartera.get(0).codigoEnviado());
        assertEquals(null, cartera.get(1).codigoEnviado(), "todavia sin invitar");
    }

    @Test
    void la_historia_de_la_deuda_intercala_los_contactos_con_lo_que_le_paso() {
        Campaign campana = new Campaign();
        campana.setName("Arriendos octubre");
        campana.setAttempts((short) 2);
        campana.setCadenceDays("[1, 4, 11]");
        Installment cuota = cuotas.getFirst();

        DebtEvent registrada = DebtEvent.de(deuda, DebtEvent.Type.registered, DebtEvent.Actor.agency)
                .conReferencia("APX-2026-09-19-004");
        registrada.setOccurredAt(el("2026-09-19T10:00"));
        DebtEvent entro = DebtEvent.de(deuda, DebtEvent.Type.portal_entered, DebtEvent.Actor.debtor);
        entro.setOccurredAt(el("2026-09-21T18:39"));
        historia.add(registrada);
        historia.add(entro);
        when(contactos.findByDebtOrderByOccurredAtAsc(deuda)).thenReturn(List.of(
                Contact.invitacion(deuda, "va**************@correo.cl").el(el("2026-09-19T10:05")),
                Contact.toque(deuda, campana, 1, "va**************@correo.cl").el(el("2026-09-20T10:00")),
                Contact.reenvio(deuda, "va**************@correo.cl").el(el("2026-09-23T09:30")),
                Contact.recordatorio(cuota, "va**************@correo.cl").el(el("2026-09-25T09:00"))));

        List<DebtDetailResponse.Suceso> sucesos = servicio.getFor(empresa("77305118-6"), 3L).historia();

        assertEquals(List.of("registered", "code_sent", "code_sent", "portal_entered", "code_sent", "code_sent"),
                sucesos.stream().map(DebtDetailResponse.Suceso::tipo).toList(), "en el orden en que paso");
        //  Cada contacto se lee como se leia cuando era un evento.
        DebtDetailResponse.Suceso invitacion = sucesos.get(1);
        assertEquals(DebtEvent.Actor.system, invitacion.quien());
        assertEquals("va**************@correo.cl", invitacion.referencia());
        assertEquals(el("2026-09-19T10:05"), invitacion.ocurrioEn());
        assertEquals(null, invitacion.monto());
        assertEquals("campana Arriendos octubre, toque 1 de 2", sucesos.get(2).referencia());
        assertEquals(DebtEvent.Actor.system, sucesos.get(2).quien());
        assertEquals(DebtEvent.Actor.agency, sucesos.get(4).quien(), "el reenvio lo pide la empresa");
        assertEquals("va**************@correo.cl", sucesos.get(4).referencia());
        assertEquals("recordatorio de la cuota que vence el 2026-09-05", sucesos.get(5).referencia());
        assertEquals(DebtEvent.Actor.system, sucesos.get(5).quien());
    }

    @Test
    void un_toque_que_ya_salio_nunca_se_lee_como_mayor_que_el_total() {
        //  La campana bajo sus intentos a 1 despues de mandar el toque 2.
        Campaign campana = new Campaign();
        campana.setName("Arriendos octubre");
        campana.setAttempts((short) 1);
        campana.setCadenceDays("[1, 4, 11]");
        when(contactos.findByDebtOrderByOccurredAtAsc(deuda)).thenReturn(List.of(
                Contact.toque(deuda, campana, 2, null).el(el("2026-09-23T10:00"))));

        assertEquals("campana Arriendos octubre, toque 2 de 2",
                servicio.getFor(empresa("77305118-6"), 3L).historia().getFirst().referencia());
    }

    @Test
    void la_agencia_que_entrego_la_cartera_la_ve_y_otra_empresa_no() {
        //  APOFYX no es la acreedora, pero envio el lote: opera esa cartera.
        assertEquals(3L, servicio.requireVisible(empresa("77305118-6"), 3L).getId());

        Organization otra = organizacion(9L, "96512345-8", "Otra");
        when(organizations.findByRut("96512345-8")).thenReturn(Optional.of(otra));
        ApiException error = assertThrows(ApiException.class,
                () -> servicio.requireVisible(empresa("96512345-8"), 3L));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
    }

    @Test
    void un_deudor_no_ve_la_deuda_de_otro() {
        Debtor felipe = new Debtor();
        felipe.setId(11L);
        when(debtors.findByRut("16482337-7")).thenReturn(Optional.of(felipe));

        ApiException error = assertThrows(ApiException.class,
                () -> servicio.getFor(deudor("16482337-7"), 3L));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
    }

    @Test
    void una_sesion_de_empresa_sin_rut_no_ve_ninguna_cartera() {
        //  Devolver "todas" seria la fuga que este diseno cerro.
        ApiException error = assertThrows(ApiException.class,
                () -> servicio.listFor(new JwtPrincipal("1", "x@y.cl", "CREDITOR", "X", null)));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
    }

    @Test
    void una_empresa_suspendida_no_ve_su_cartera() {
        Organization suspendida = organizacion(10L, "76999888-7", "Suspendida");
        suspendida.setStatus(Organization.Status.suspended);
        when(organizations.findByRut("76999888-7")).thenReturn(Optional.of(suspendida));

        ApiException error = assertThrows(ApiException.class,
                () -> servicio.listFor(empresa("76999888-7")));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        assertEquals("La empresa está suspendida", error.getMessage());
    }

    @Test
    void ms_payments_cobra_el_saldo_y_la_cuota_que_vence_primero() {
        DebtSnapshotResponse snapshot = servicio.snapshotInterno(3L, null);

        assertEquals(new BigDecimal("410000"), snapshot.amount());
        assertEquals(12L, snapshot.installmentId());
        assertEquals("18905214-6", snapshot.debtorRut());
    }

    private PagoConfirmado aviso() {
        return new PagoConfirmado(PagoConfirmado.TIPO, 41L, 3L, null, "18905214-6", "76418902-7",
                new BigDecimal("410000"), "CLP", 410000L, null, "webpay", "wp-9f31c2", Instant.now());
    }

    @Test
    void un_pago_que_cubre_el_saldo_deja_la_deuda_pagada_y_avisa() {
        servicio.onPagoConfirmado(aviso());

        assertEquals(Installment.Status.paid, cuotas.getFirst().getStatus());
        assertEquals(Debt.Status.paid, deuda.getStatus());
        verify(eventos).publicar(eq(deuda), eq(EventosService.PAGO_CONFIRMADO), any(), any());
        verify(eventos).publicar(eq(deuda), eq(EventosService.DEUDA_SALDADA), any(), any());
    }

    @Test
    void el_mismo_aviso_dos_veces_se_abona_una_sola() {
        servicio.onPagoConfirmado(aviso());
        servicio.onPagoConfirmado(aviso());

        ArgumentCaptor<DebtEvent> anotados = ArgumentCaptor.forClass(DebtEvent.class);
        verify(events, org.mockito.Mockito.times(2)).save(anotados.capture());
        //  payment_applied y settled, de la primera vez. La segunda no anota nada.
        assertEquals(List.of(DebtEvent.Type.payment_applied, DebtEvent.Type.settled),
                anotados.getAllValues().stream().map(DebtEvent::getType).toList());
    }

    @Test
    void la_empresa_no_repacta_por_el_deudor() {
        ApiException error = assertThrows(ApiException.class,
                () -> servicio.applyRepact(empresa("77305118-6"), 3L, 6));
        assertEquals(HttpStatus.FORBIDDEN, error.getStatus());
        verify(installments, never()).save(any());
    }

    @Test
    void repactar_anula_las_cuotas_pendientes_en_vez_de_borrarlas() {
        when(installments.findByDebtOrderByNumberAsc(deuda)).thenReturn(cuotas);
        when(installments.save(any())).thenAnswer(llamada -> llamada.getArgument(0));

        servicio.applyRepact(deudor("18905214-6"), 3L, 3);

        assertEquals(Installment.Status.void_, cuotas.getFirst().getStatus());
        assertEquals(Debt.Status.repacted, deuda.getStatus());
        verify(eventos).publicar(eq(deuda), eq(EventosService.REPACTACION_ACEPTADA), any(), any());
    }

    // ------------------------------------------------------------------
    //  Intereses
    // ------------------------------------------------------------------

    private static final LocalDate HOY = LocalDate.now(ZoneId.of("America/Santiago"));

    /** La deuda con tasa, una sola cuota de 410.000 y su cargo, vencidos hace 30 dias. */
    private void conTasa(String tasa) {
        deuda.setInterestRate(new BigDecimal(tasa));
        cuotas.getFirst().setDueDate(HOY.minusDays(30));
        DebtCharge cargo = new DebtCharge();
        cargo.setDebt(deuda);
        cargo.setConcept("Arriendo");
        cargo.setAmount(new BigDecimal("410000"));
        cargo.setDueDate(HOY.minusDays(30));
        when(charges.findByDebtOrderByDueDateAsc(deuda)).thenReturn(List.of(cargo));
    }

    @Test
    void con_tasa_el_cobro_suma_la_mora_de_las_cuotas_y_la_informa_aparte() {
        conTasa("1.5");

        DebtSnapshotResponse snapshot = servicio.snapshotInterno(3L, null);

        //  410.000 x 1,5% x 30/30
        assertEquals(new BigDecimal("410000"), snapshot.capital());
        assertEquals(new BigDecimal("6150"), snapshot.interes());
        assertEquals(new BigDecimal("416150"), snapshot.amount());
    }

    @Test
    void sin_tasa_el_cobro_es_el_de_siempre() {
        DebtSnapshotResponse snapshot = servicio.snapshotInterno(3L, null);

        assertEquals(new BigDecimal("410000"), snapshot.amount());
        assertEquals(BigDecimal.ZERO, snapshot.interes());
        verify(charges, never()).findByDebtOrderByDueDateAsc(any());
    }

    // ------------------------------------------------------------------
    //  Descuento por pronto pago (contrato §7.1)
    // ------------------------------------------------------------------

    /**
     * El ejemplo del contrato: tres arriendos de $300.000 al 2%, atrasados 62,
     * 31 y 1 dia ($18.800 de mora), en una campana que condona el 50% del
     * tramo 31-90 hasta el 3 de noviembre.
     */
    private void tresArriendosEnCampana() {
        deuda.setInterestRate(new BigDecimal("2"));
        deuda.setStatus(Debt.Status.open);
        cuotas.getFirst().setAmount(new BigDecimal("900000"));
        cuotas.getFirst().setDueDate(HOY.minusDays(1));
        List<DebtCharge> cargos = new ArrayList<>();
        for (int dias : new int[]{62, 31, 1}) {
            DebtCharge cargo = new DebtCharge();
            cargo.setDebt(deuda);
            cargo.setAmount(new BigDecimal("300000"));
            cargo.setDueDate(HOY.minusDays(dias));
            cargos.add(cargo);
        }
        when(charges.findByDebtOrderByDueDateAsc(deuda)).thenReturn(cargos);
        Campaign campana = new Campaign();
        campana.setMoraDiscount("{\"1-30\":0,\"31-90\":50,\"91-120\":100}");
        campana.setEndsOn(LocalDate.of(2026, 11, 3));
        deuda.setCampaign(campana);
        when(descuentos.porcentaje(eq(deuda), eq(62L), any())).thenReturn(new BigDecimal("50"));
    }

    @Test
    void pagando_toda_la_deuda_durante_la_campana_se_condona_la_mitad_de_la_mora() {
        tresArriendosEnCampana();

        DebtSnapshotResponse snapshot = servicio.snapshotInterno(3L, null);

        assertEquals(new BigDecimal("900000"), snapshot.capital());
        assertEquals(new BigDecimal("9400"), snapshot.descuento());
        assertEquals(new BigDecimal("9400"), snapshot.interes(), "la mora que se cobra, ya sin el descuento");
        assertEquals(new BigDecimal("909400"), snapshot.amount());
    }

    @Test
    void la_deuda_muestra_el_descuento_y_hasta_cuando_vale() {
        tresArriendosEnCampana();

        DebtDetailResponse detalle = servicio.getFor(deudor("18905214-6"), 3L);

        assertEquals(new BigDecimal("18800"), detalle.resumen().interesMora());
        assertEquals(new BigDecimal("9400"), detalle.resumen().descuentoDisponible());
        assertEquals(LocalDate.of(2026, 11, 3), detalle.resumen().descuentoHasta());
        assertEquals(new BigDecimal("918800"), detalle.resumen().totalHoy(), "el total no resta el descuento");
    }

    @Test
    void un_pago_de_parte_de_la_deuda_no_tiene_descuento() {
        tresArriendosEnCampana();
        Installment siguiente = new Installment();
        siguiente.setId(13L);
        siguiente.setDebt(deuda);
        siguiente.setNumber((short) 2);
        siguiente.setDueDate(HOY.plusDays(20));
        siguiente.setAmount(new BigDecimal("300000"));
        cuotas.add(siguiente);

        DebtSnapshotResponse snapshot = servicio.snapshotInterno(3L, List.of(12L));

        assertEquals(0, snapshot.descuento().signum());
        assertEquals(new BigDecimal("918800"), snapshot.amount());
    }

    @Test
    void en_convenio_no_hay_descuento() {
        tresArriendosEnCampana();
        deuda.setStatus(Debt.Status.repacted);

        assertEquals(0, servicio.snapshotInterno(3L, null).descuento().signum());
        verify(descuentos, never()).porcentaje(any(), anyLong(), any());
    }

    @Test
    void el_aviso_lleva_el_descuento_hasta_el_acreedor() throws Exception {
        servicio.onPagoConfirmado(new PagoConfirmado(PagoConfirmado.TIPO, 45L, 3L, null, "18905214-6",
                "76418902-7", new BigDecimal("909400"), "CLP", 909400L, null, "khipu", "k-descuento",
                Instant.now(), List.of(12L), new BigDecimal("9400"), new BigDecimal("9400")));

        ArgumentCaptor<Object> datos = ArgumentCaptor.forClass(Object.class);
        verify(eventos).publicar(eq(deuda), eq(EventosService.PAGO_CONFIRMADO), datos.capture(), any());
        PagoConfirmadoDatos pago = (PagoConfirmadoDatos) datos.getValue();
        assertEquals(909400L, pago.monto());
        assertEquals(900000L, pago.capital());
        assertEquals(9400L, pago.interes());
        assertEquals(9400L, pago.descuento());
        DebtEvent aplicado = historia.stream().filter(e -> e.getType() == DebtEvent.Type.payment_applied)
                .findFirst().orElseThrow();
        assertEquals(new BigDecimal("9400"), json.readValue(aplicado.getDetail(), DetallePago.class).descuento());
    }

    @Test
    void con_toda_la_mora_condonada_el_aviso_igual_separa_el_capital() {
        servicio.onPagoConfirmado(new PagoConfirmado(PagoConfirmado.TIPO, 46L, 3L, null, "18905214-6",
                "76418902-7", new BigDecimal("410000"), "CLP", 410000L, null, "khipu", "k-todo",
                Instant.now(), List.of(12L), BigDecimal.ZERO, new BigDecimal("6150")));

        ArgumentCaptor<Object> datos = ArgumentCaptor.forClass(Object.class);
        verify(eventos).publicar(eq(deuda), eq(EventosService.PAGO_CONFIRMADO), datos.capture(), any());
        PagoConfirmadoDatos pago = (PagoConfirmadoDatos) datos.getValue();
        assertEquals(410000L, pago.capital());
        assertEquals(0L, pago.interes());
        assertEquals(6150L, pago.descuento());
    }

    @Test
    void la_deuda_muestra_la_mora_de_hoy_y_cuanto_se_paga_en_total() {
        conTasa("1.5");

        DebtDetailResponse detalle = servicio.getFor(deudor("18905214-6"), 3L);

        assertEquals(new BigDecimal("1.5"), detalle.resumen().tasaInteresMensual());
        assertEquals(new BigDecimal("6150"), detalle.resumen().interesMora());
        assertEquals(new BigDecimal("416150"), detalle.resumen().totalHoy());
        assertEquals(new BigDecimal("6150"), detalle.cuotas().getFirst().interesMora());
    }

    @Test
    void el_pago_abona_las_cuotas_que_cobro_aunque_el_monto_traiga_intereses() {
        Installment siguiente = new Installment();
        siguiente.setId(13L);
        siguiente.setDebt(deuda);
        siguiente.setNumber((short) 2);
        siguiente.setDueDate(LocalDate.of(2026, 10, 5));
        siguiente.setAmount(new BigDecimal("6150"));
        cuotas.add(siguiente);

        //  410.000 de capital mas 6.150 de mora: el monto alcanzaria para la cuota 13, pero no se cobro.
        servicio.onPagoConfirmado(new PagoConfirmado(PagoConfirmado.TIPO, 41L, 3L, null, "18905214-6",
                "76418902-7", new BigDecimal("416150"), "CLP", 416150L, null, "khipu", "k-mora",
                Instant.now(), List.of(12L), new BigDecimal("6150")));

        assertEquals(Installment.Status.paid, cuotas.getFirst().getStatus());
        assertEquals(Installment.Status.pending, siguiente.getStatus());
        ArgumentCaptor<Object> datos = ArgumentCaptor.forClass(Object.class);
        verify(eventos).publicar(eq(deuda), eq(EventosService.PAGO_CONFIRMADO), datos.capture(), any());
        PagoConfirmadoDatos pago = (PagoConfirmadoDatos) datos.getValue();
        assertEquals(410000L, pago.capital(), "el acreedor imputa el capital a sus cargos");
        assertEquals(6150L, pago.interes(), "y registra la mora aparte");
        assertEquals(416150L, pago.monto());
    }

    @Test
    void con_tasa_el_convenio_suma_la_mora_y_sus_cuotas_llevan_interes() {
        conTasa("1");
        when(installments.save(any())).thenAnswer(llamada -> llamada.getArgument(0));

        RepactPlan plan = servicio.simulate(deudor("18905214-6"), 3L, 3);
        servicio.applyRepact(deudor("18905214-6"), 3L, 3);

        //  410.000 x 1% x 30/30 = 4.100 de mora, que se repactan junto con el capital.
        assertEquals(new BigDecimal("4100"), plan.interesMora());
        assertEquals(new BigDecimal("414100"), plan.aRepactar());
        assertEquals(new BigDecimal("1"), plan.tasaInteresMensual());
        ArgumentCaptor<Installment> nuevas = ArgumentCaptor.forClass(Installment.class);
        verify(installments, org.mockito.Mockito.atLeast(3)).save(nuevas.capture());
        List<Installment> delPlan = nuevas.getAllValues().stream().filter(Installment::enConvenio).toList();
        assertEquals(3, delPlan.size());
        assertEquals(plan.cuotas().getFirst().interest(), delPlan.getFirst().getInterestAmount(),
                "cada cuota guarda su interes, para que la mora corra solo sobre el capital");
        //  Al 1% mensual en sistema frances, la CAE es 12,00%: el redondeo de las cuotas no la mueve.
        assertEquals(new BigDecimal("12.00"), plan.cae());
        ArgumentCaptor<Repactation> convenio = ArgumentCaptor.forClass(Repactation.class);
        verify(repactations).save(convenio.capture());
        assertEquals(new BigDecimal("12.00"), convenio.getValue().getCae(), "el convenio guarda la CAE que se informo");

        ArgumentCaptor<Object> datos = ArgumentCaptor.forClass(Object.class);
        verify(eventos).publicar(eq(deuda), eq(EventosService.REPACTACION_ACEPTADA), datos.capture(), any());
        RepactacionAceptadaDatos aceptada = (RepactacionAceptadaDatos) datos.getValue();
        assertEquals(new BigDecimal("1"), aceptada.tasaInteresMensual());
        assertEquals(414100L, aceptada.aRepactar());
        assertEquals(new BigDecimal("12.00"), aceptada.cae(), "el aviso repactacion.aceptada lleva la CAE");
    }

    // ------------------------------------------------------------------
    //  La CAE del convenio (#46)
    // ------------------------------------------------------------------

    private Repactation convenioVigente(String cae, String aceptado) {
        Repactation convenio = new Repactation();
        convenio.setDebt(deuda);
        convenio.setCae(new BigDecimal(cae));
        convenio.setAcceptedAt(Instant.parse(aceptado));
        return convenio;
    }

    @Test
    void el_detalle_de_un_convenio_trae_su_cae_y_si_hubiera_dos_vigentes_la_del_ultimo() {
        enConvenioDeTres();
        when(repactations.findByDebtInAndSupersededAtIsNull(List.of(deuda))).thenReturn(List.of(
                convenioVigente("18.00", "2026-10-02T15:00:00Z"),
                convenioVigente("15.00", "2026-09-10T12:00:00Z")));

        DebtDetailResponse detalle = servicio.getFor(deudor("18905214-6"), 3L);

        assertEquals(new BigDecimal("18.00"), detalle.resumen().cae());
    }

    @Test
    void una_deuda_que_ya_no_esta_en_convenio_no_informa_la_cae_del_convenio_viejo() {
        //  El acreedor la volvio a cargar: queda abierta, con el convenio anterior sin reemplazar.
        when(repactations.findByDebtInAndSupersededAtIsNull(List.of(deuda)))
                .thenReturn(List.of(convenioVigente("18.00", "2026-10-02T15:00:00Z")));

        DebtDetailResponse detalle = servicio.getFor(deudor("18905214-6"), 3L);

        assertEquals(false, detalle.resumen().conConvenio());
        assertNull(detalle.resumen().cae());
    }

    @Test
    void las_listas_traen_la_cae_en_una_sola_consulta() {
        enConvenioDeTres();
        when(debts.findByDebtorOrderByUpdatedAtDesc(valentina)).thenReturn(List.of(deuda));
        when(debts.carteraDe(apofyx)).thenReturn(List.of(deuda));
        when(repactations.findByDebtInAndSupersededAtIsNull(List.of(deuda)))
                .thenReturn(List.of(convenioVigente("18.00", "2026-10-02T15:00:00Z")));

        assertEquals(new BigDecimal("18.00"), servicio.listFor(deudor("18905214-6")).getFirst().cae());
        assertEquals(new BigDecimal("18.00"), servicio.listFor(empresa("77305118-6")).getFirst().cae());
        verify(repactations, org.mockito.Mockito.times(2)).findByDebtInAndSupersededAtIsNull(List.of(deuda));
        verify(repactations, never()).findByDebtAndSupersededAtIsNull(any());
    }

    // ------------------------------------------------------------------
    //  Una empresa suspendida (#152): lo suyo queda congelado
    // ------------------------------------------------------------------

    @Test
    void con_el_acreedor_suspendido_la_deuda_no_se_cobra_ni_se_repacta() {
        patrimonio.setStatus(Organization.Status.suspended);

        ApiException cobrar = assertThrows(ApiException.class, () -> servicio.snapshotInterno(3L, null));
        ApiException repactar = assertThrows(ApiException.class,
                () -> servicio.applyRepact(deudor("18905214-6"), 3L, 3));

        assertEquals(HttpStatus.CONFLICT, cobrar.getStatus());
        assertEquals("La empresa que cobra esta deuda está suspendida: por ahora no se puede pagar",
                cobrar.getMessage());
        assertEquals(HttpStatus.CONFLICT, repactar.getStatus());
        verify(installments, never()).save(any());
        assertTrue(DebtSummaryResponse.from(deuda, cuotas, null).enPausa(), "el portal del deudor lo sabe");
    }

    @Test
    void reactivada_la_empresa_la_deuda_se_cobra_como_antes() {
        patrimonio.setStatus(Organization.Status.suspended);
        assertThrows(ApiException.class, () -> servicio.snapshotInterno(3L, null));

        patrimonio.setStatus(Organization.Status.active);

        assertEquals(new BigDecimal("410000"), servicio.snapshotInterno(3L, null).amount());
        assertFalse(DebtSummaryResponse.from(deuda, cuotas, null).enPausa());
    }

    @Test
    void con_la_agencia_de_su_campana_o_de_su_mandato_suspendida_tambien_se_congela() {
        Campaign campana = new Campaign();
        campana.setAgency(apofyx);
        campana.setCreditor(patrimonio);
        deuda.setCampaign(campana);
        apofyx.setStatus(Organization.Status.suspended);
        assertTrue(deuda.congelada(), "por la agencia de su campana");

        deuda.setCampaign(null);
        Mandate mandato = new Mandate();
        mandato.setAgency(apofyx);
        deuda.setMandate(mandato);
        assertTrue(deuda.congelada(), "por la agencia de su mandato");
        assertEquals(HttpStatus.CONFLICT,
                assertThrows(ApiException.class, () -> servicio.snapshotInterno(3L, null)).getStatus());

        apofyx.setStatus(Organization.Status.active);
        assertFalse(deuda.congelada());
    }
}
