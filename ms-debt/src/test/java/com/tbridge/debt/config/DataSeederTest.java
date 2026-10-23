package com.tbridge.debt.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.debt.model.*;
import com.tbridge.debt.repository.*;
import com.tbridge.debt.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** La demo recorre los servicios reales; solo se simula la persistencia y la salida de avisos. */
class DataSeederTest {

    private final OrganizationRepository organizaciones = mock(OrganizationRepository.class);
    private final DebtorRepository deudores = mock(DebtorRepository.class);
    private final DebtRepository deudas = mock(DebtRepository.class);
    private final CampaignRepository campanas = mock(CampaignRepository.class);
    private final BatchRepository lotes = mock(BatchRepository.class);
    private final DebtChargeRepository cargos = mock(DebtChargeRepository.class);
    private final InstallmentRepository cuotas = mock(InstallmentRepository.class);
    private final RepactationRepository convenios = mock(RepactationRepository.class);
    private final DebtEventRepository eventos = mock(DebtEventRepository.class);
    private final MandateRepository mandatos = mock(MandateRepository.class);
    private final ContactRepository contactos = mock(ContactRepository.class);
    private final EventosService avisos = mock(EventosService.class);
    private final ApplicationEventPublisher publicador = mock(ApplicationEventPublisher.class);
    private final ObjectMapper json = new ObjectMapper();
    private final List<Organization> empresas = new ArrayList<>();
    private final List<Debtor> personas = new ArrayList<>();
    private final List<Debt> cartera = new ArrayList<>();
    private final List<Campaign> campanasGuardadas = new ArrayList<>();
    private final List<Batch> lotesGuardados = new ArrayList<>();
    private final List<DebtCharge> cargosGuardados = new ArrayList<>();
    private final List<Installment> cuotasGuardadas = new ArrayList<>();
    private final List<DebtEvent> historia = new ArrayList<>();
    private DataSeeder seeder;
    private DebtService servicio;

    @BeforeEach
    void preparar() {
        when(organizaciones.count()).thenAnswer(i -> (long) empresas.size());
        when(deudores.count()).thenAnswer(i -> (long) personas.size());
        when(deudas.count()).thenAnswer(i -> (long) cartera.size());
        when(organizaciones.save(any())).thenAnswer(i -> {
            Organization e = i.getArgument(0);
            if (e.getId() == null) { e.setId((long) empresas.size() + 1); empresas.add(e); }
            return e;
        });
        when(organizaciones.findByRut(any())).thenAnswer(i -> empresas.stream()
                .filter(e -> e.getRut().equals(i.getArgument(0))).findFirst());
        when(deudores.save(any())).thenAnswer(i -> {
            Debtor d = i.getArgument(0);
            if (d.getId() == null) { d.setId((long) personas.size() + 1); personas.add(d); }
            return d;
        });
        when(deudores.findByRut(any())).thenAnswer(i -> personas.stream()
                .filter(d -> d.getRut().equals(i.getArgument(0))).findFirst());
        when(campanas.save(any())).thenAnswer(i -> {
            Campaign c = i.getArgument(0);
            if (c.getId() == null) { c.setId((long) campanasGuardadas.size() + 1); campanasGuardadas.add(c); }
            return c;
        });
        when(campanas.findByAgencyAndExternalId(any(), any())).thenAnswer(i -> campanasGuardadas.stream()
                .filter(c -> c.getAgency() == i.getArgument(0) && c.getExternalId().equals(i.getArgument(1))).findFirst());
        when(lotes.save(any())).thenAnswer(i -> {
            Batch b = i.getArgument(0);
            if (b.getId() == null) { b.setId((long) lotesGuardados.size() + 1); lotesGuardados.add(b); }
            return b;
        });
        when(lotes.findBySenderAndExternalId(any(), any())).thenAnswer(i -> lotesGuardados.stream()
                .filter(b -> b.getSender() == i.getArgument(0) && b.getExternalId().equals(i.getArgument(1))).findFirst());
        when(deudas.save(any())).thenAnswer(i -> {
            Debt d = i.getArgument(0);
            if (d.getId() == null) { d.setId((long) cartera.size() + 1); cartera.add(d); }
            return d;
        });
        when(deudas.findByCreditorAndExternalId(any(), any())).thenAnswer(i -> cartera.stream()
                .filter(d -> d.getCreditor() == i.getArgument(0) && d.getExternalId().equals(i.getArgument(1))).findFirst());
        when(deudas.findById(any())).thenAnswer(i -> cartera.stream()
                .filter(d -> d.getId().equals(i.getArgument(0))).findFirst());
        when(cargos.save(any())).thenAnswer(i -> { DebtCharge c = i.getArgument(0); cargosGuardados.add(c); return c; });
        when(cargos.findByDebtOrderByDueDateAsc(any())).thenAnswer(i -> cargosGuardados.stream()
                .filter(c -> c.getDebt() == i.getArgument(0)).sorted(Comparator.comparing(DebtCharge::getDueDate)).toList());
        when(cuotas.save(any())).thenAnswer(i -> {
            Installment c = i.getArgument(0);
            if (c.getId() == null) { c.setId((long) cuotasGuardadas.size() + 1); cuotasGuardadas.add(c); }
            return c;
        });
        when(cuotas.findById(any())).thenAnswer(i -> cuotasGuardadas.stream()
                .filter(c -> c.getId().equals(i.getArgument(0))).findFirst());
        when(cuotas.findByDebtOrderByNumberAsc(any())).thenAnswer(i -> cuotasGuardadas.stream()
                .filter(c -> c.getDebt() == i.getArgument(0)).sorted(Comparator.comparing(Installment::getNumber)).toList());
        when(cuotas.findByDebtAndStatus(any(), any())).thenAnswer(i -> cuotasGuardadas.stream()
                .filter(c -> c.getDebt() == i.getArgument(0) && c.getStatus() == i.getArgument(1)).toList());
        when(eventos.save(any())).thenAnswer(i -> { DebtEvent e = i.getArgument(0); historia.add(e); return e; });
        when(eventos.findByDebtOrderByOccurredAtAsc(any())).thenAnswer(i -> historia.stream()
                .filter(e -> e.getDebt() == i.getArgument(0)).toList());
        when(eventos.entre(any(), any(), any())).thenAnswer(i -> historia.stream()
                .filter(e -> e.getType() == i.getArgument(0)).toList());
        DescuentoService descuentos = new DescuentoService(mandatos, json);
        servicio = new DebtService(deudas, deudores, organizaciones, cargos, cuotas, convenios, eventos,
                contactos, new RepactationService(), avisos, json, descuentos);
        CarteraIntakeService ingesta = new CarteraIntakeService(organizaciones, mandatos, campanas, lotes,
                deudores, deudas, cargos, cuotas, eventos, json, avisos, publicador, 30, new BigDecimal("3.0"));
        seeder = construir(ingesta, descuentos, true);
    }

    private DataSeeder construir(CarteraIntakeService ingesta, DescuentoService descuentos, boolean habilitada) {
        return new DataSeeder(organizaciones, deudores, deudas, cuotas,
                new MandatoService(organizaciones, mandatos, campanas, descuentos), ingesta, servicio,
                new DisputaService(servicio, deudas, cuotas, eventos, avisos, json), json, habilitada);
    }

    @Test
    void apagada_no_consulta_ni_crea_datos() {
        construir(null, null, false).run();
        verifyNoInteractions(organizaciones, deudores, deudas, campanas, lotes, cuotas, eventos, avisos);
    }

    @Test
    void una_base_con_una_empresa_propia_no_se_completa_con_datos_ficticios() {
        Organization propia = new Organization();
        propia.setTradeName("Mi empresa");
        empresas.add(propia);
        seeder.run();
        assertEquals(List.of(propia), empresas);
        assertTrue(cartera.isEmpty());
        verify(organizaciones, never()).save(any());
        verifyNoInteractions(campanas, lotes, cuotas, avisos);
    }

    @Test
    void tampoco_siembra_sobre_deudores_preexistentes() {
        personas.add(new Debtor());
        seeder.run();
        verify(organizaciones, never()).save(any());
        verifyNoInteractions(campanas, lotes, cuotas, avisos);
    }

    @Test
    void tampoco_siembra_si_ya_hay_deudas() {
        cartera.add(new Debt());
        seeder.run();
        verify(organizaciones, never()).save(any());
        verifyNoInteractions(campanas, lotes, cuotas, avisos);
    }

    @Test
    void las_seis_deudas_entran_por_la_ingesta_en_dos_campanas_propias_sin_mandatos() {
        seeder.run();
        assertEquals(1, empresas.size());
        assertEquals(Organization.Kind.creditor, empresas.getFirst().getKind());
        assertEquals(6, cartera.size());
        assertEquals(2, campanasGuardadas.size());
        assertEquals(2, lotesGuardados.size());
        assertEquals(12, cargosGuardados.size());
        for (Debt d : cartera) {
            assertNotNull(d.getCampaign());
            assertSame(empresas.getFirst(), d.getCreditor());
            assertSame(d.getCreditor(), d.getCampaign().getAgency());
            assertSame(d.getCreditor(), d.getLastBatch().getSender());
            assertNull(ReflectionTestUtils.getField(d, "mandate"));
            assertTrue(d.getDebtor().getEmail().endsWith("@example.com"));
            assertTrue(cargosGuardados.stream().filter(c -> c.getDebt() == d)
                    .anyMatch(c -> ChronoUnit.DAYS.between(c.getDueDate(),
                            (LocalDate) ReflectionTestUtils.getField(d.getLastBatch(), "cutOff")) >= 30));
        }
        assertTrue(lotesGuardados.stream().allMatch(b -> b.getSource() == Batch.Source.file && b.getRejectedCount() == 0));
        verify(mandatos, never()).save(any());
    }

    @Test
    void conserva_las_situaciones_de_convenio_pagada_reclamo_uf_y_riesgo() {
        seeder.run();
        assertEquals(Debt.Status.repacted, deuda("DEMO-CONVENIO").getStatus());
        assertEquals(3, cuotasGuardadas.stream().filter(c -> c.getDebt() == deuda("DEMO-CONVENIO")
                && c.getStatus() == Installment.Status.paid).count());
        assertEquals(Debt.Status.paid, deuda("DEMO-PAGADA").getStatus());
        assertEquals(0, servicio.saldo(deuda("DEMO-PAGADA")).signum());
        assertEquals(Debt.Status.disputed, deuda("DEMO-RECLAMO").getStatus());
        assertEquals(Debt.Currency.UF, deuda("DEMO-UF-ABIERTA").getCurrency());
        assertEquals(Debt.Status.open, deuda("DEMO-UF-ABIERTA").getStatus());
        assertTrue(cuotasGuardadas.stream().anyMatch(c -> c.getDebt() == deuda("DEMO-RIESGO")
                && c.enConvenio() && c.getStatus() == Installment.Status.pending && c.getDueDate().isBefore(LocalDate.now())));
    }

    @Test
    void los_pagos_son_simulados_y_conservan_los_pesos_y_la_atribucion_de_la_comision() throws Exception {
        seeder.run();
        List<DebtEvent> pagos = historia.stream().filter(e -> e.getType() == DebtEvent.Type.payment_applied).toList();
        assertEquals(3, pagos.size());
        for (DebtEvent pago : pagos) {
            DetallePago detalle = json.readValue(pago.getDetail(), DetallePago.class);
            assertEquals("demo", detalle.pasarela());
            assertEquals(DataSeeder.EMPRESA_RUT, detalle.empresaRut());
            assertNotNull(detalle.campanaId());
            assertNotNull(detalle.montoClp());
        }
        var comision = new ComisionService(eventos, servicio, json).deTodas(YearMonth.now());
        assertEquals(1_918_024, comision.recuperado());
        assertEquals(191_802, comision.comision());
    }

    @Test
    void reiniciar_no_duplica_ni_restaura_lo_que_la_empresa_cambio() {
        seeder.run();
        empresas.getFirst().setTradeName("Nombre cambiado por la empresa");
        deuda("DEMO-RECLAMO").setStatus(Debt.Status.withdrawn);
        int eventosAntes = historia.size();
        int cuotasAntes = cuotasGuardadas.size();
        seeder.run();
        assertEquals(1, empresas.size());
        assertEquals(6, cartera.size());
        assertEquals(2, lotesGuardados.size());
        assertEquals(eventosAntes, historia.size());
        assertEquals(cuotasAntes, cuotasGuardadas.size());
        assertEquals("Nombre cambiado por la empresa", empresas.getFirst().getTradeName());
        assertEquals(Debt.Status.withdrawn, deuda("DEMO-RECLAMO").getStatus());
    }

    private Debt deuda(String id) {
        return cartera.stream().filter(d -> d.getExternalId().equals(id)).findFirst().orElseThrow();
    }
}
