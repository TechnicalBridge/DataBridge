package com.tbridge.debt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tbridge.debt.dto.response.CarteraResponse;
import com.tbridge.debt.dto.response.CarteraResponse.ResultadoDeuda;
import com.tbridge.debt.exception.CarteraInvalida;
import com.tbridge.debt.model.Batch;
import com.tbridge.debt.model.Campaign;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.DebtCharge;
import com.tbridge.debt.model.Debtor;
import com.tbridge.debt.model.Installment;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.model.Repactation;
import com.tbridge.debt.repository.BatchRepository;
import com.tbridge.debt.repository.CampaignRepository;
import com.tbridge.debt.repository.DebtChargeRepository;
import com.tbridge.debt.repository.DebtEventRepository;
import com.tbridge.debt.repository.DebtRepository;
import com.tbridge.debt.repository.DebtorRepository;
import com.tbridge.debt.repository.InstallmentRepository;
import com.tbridge.debt.repository.MandateRepository;
import com.tbridge.debt.repository.OrganizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * El alcance de DataBridge: solo deudores morosos. Una deuda entra cuando su
 * cargo impago mas antiguo lleva al menos 30 dias vencido, sea un arriendo
 * mensual o una boleta de un solo cargo; una que ya esta en gestion puede
 * volver con menos.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CarteraIntakeServiceTest {

    @Mock private OrganizationRepository organizations;
    @Mock private MandateRepository mandates;
    @Mock private CampaignRepository campaigns;
    @Mock private BatchRepository batches;
    @Mock private DebtorRepository debtors;
    @Mock private DebtRepository debts;
    @Mock private DebtChargeRepository charges;
    @Mock private InstallmentRepository installments;
    @Mock private DebtEventRepository events;
    @Mock private EventosService eventos;
    @Mock private ApplicationEventPublisher avisos;

    private final ObjectMapper json = new ObjectMapper();
    private CarteraIntakeService servicio;
    private Organization patrimonio;

    @BeforeEach
    void preparar() {
        servicio = new CarteraIntakeService(organizations, mandates, campaigns, batches, debtors, debts,
                charges, installments, events, json, eventos, avisos, 30, new BigDecimal("3.0"));

        //  Patrimonio entrega lo suyo: sin agencia, no hace falta mandato.
        patrimonio = new Organization();
        patrimonio.setId(1L);
        patrimonio.setRut("76418902-7");
        patrimonio.setTradeName("Patrimonio Inmuebles");
        when(organizations.findByRut("76418902-7")).thenReturn(Optional.of(patrimonio));
        when(batches.findBySenderAndExternalId(any(), any())).thenReturn(Optional.empty());
        when(debts.findByCreditorAndExternalId(any(), any())).thenReturn(Optional.empty());
        when(debtors.findByRut(any())).thenReturn(Optional.empty());
        when(debtors.save(any(Debtor.class))).thenAnswer(llamada -> llamada.getArgument(0));
    }

    private JsonNode cartera(String... cargos) throws Exception {
        return carteraAl("2026-09-18", cargos);
    }

    private JsonNode carteraAl(String corte, String... cargos) throws Exception {
        return json.readTree("""
                {"version": "1.0",
                 "lote": {"id_externo": "PAT-%s-01", "fecha_corte": "%s",
                          "acreedor": {"rut": "76418902-7"}},
                 "deudas": [{"id_externo": "CTR-2026-031", "moneda": "CLP", "concepto": "Arriendo mensual",
                             "deudor": {"rut": "18905214-6", "tipo": "persona",
                                        "nombre": "Valentina Soto Pizarro",
                                        "correo": "valentina.soto@correo.cl"},
                             "cargos": [%s]}]}
                """.formatted(corte, corte, String.join(",", cargos)));
    }

    private static String cargo(String concepto, String periodo, String vence) {
        return cargo(concepto, periodo, vence, 410000);
    }

    private static String cargo(String concepto, String periodo, String vence, long monto) {
        return """
                {"concepto": "%s", "periodo": "%s", "monto": %d, "fecha_vencimiento": "%s"}"""
                .formatted(concepto, periodo, monto, vence);
    }

    private ResultadoDeuda recibir(JsonNode payload) {
        CarteraResponse respuesta = servicio.recibir(patrimonio, payload, Batch.Source.api);
        return respuesta.resultados().getFirst();
    }

    @Test
    void con_menos_de_30_dias_de_mora_todavia_no_es_morosa_y_no_entra() throws Exception {
        ResultadoDeuda resultado = recibir(cartera(cargo("Arriendo septiembre", "2026-09", "2026-09-05")));

        assertEquals("rechazada", resultado.resultado());
        assertEquals("bajo_umbral_mora", resultado.errores().getFirst().codigo());
        assertEquals("Tiene 13 dias de mora: DataBridge recibe deudas desde 30 dias de mora",
                resultado.errores().getFirst().mensaje());
        verify(debts, never()).save(any());
    }

    @Test
    void con_dos_meses_impagos_entra_a_cobranza() throws Exception {
        ResultadoDeuda resultado = recibir(cartera(
                cargo("Arriendo agosto", "2026-08", "2026-08-05"),
                cargo("Arriendo septiembre", "2026-09", "2026-09-05")));

        assertEquals("registrada", resultado.resultado());
        assertEquals(44L, resultado.moraDias());
    }

    @Test
    void el_umbral_se_cumple_justo_a_los_30_dias() throws Exception {
        assertEquals("registrada", recibir(carteraAl("2026-09-18",
                cargo("Arriendo agosto", "2026-08", "2026-08-19"))).resultado());
        assertEquals("rechazada", recibir(carteraAl("2026-09-17",
                cargo("Arriendo agosto", "2026-08", "2026-08-19"))).resultado());
    }

    @Test
    void una_deuda_de_un_solo_cargo_con_mora_entra_aunque_no_sea_mensual() throws Exception {
        //  Un tratamiento dental o una boleta de clinica: un cargo, sin meses.
        //  Con la regla de meses impagos se rechazaba aunque llevara 75 dias.
        ResultadoDeuda resultado = recibir(cartera(cargo("Tratamiento de ortodoncia", "", "2026-07-05", 890000)));

        assertEquals("registrada", resultado.resultado());
        assertEquals(75L, resultado.moraDias());
    }

    @Test
    void cuenta_el_cargo_mas_antiguo_y_no_cuantos_cargos_hay() throws Exception {
        //  Tres cargos recientes no hacen moroso a nadie: el mas antiguo vencio hace 13 dias.
        ResultadoDeuda resultado = recibir(cartera(
                cargo("Arriendo septiembre", "2026-09", "2026-09-05"),
                cargo("Gasto comun septiembre", "2026-09", "2026-09-10"),
                cargo("Multa por atraso", "2026-09", "2026-09-15")));

        assertEquals("rechazada", resultado.resultado());
        assertEquals("bajo_umbral_mora", resultado.errores().getFirst().codigo());
    }

    @Test
    void una_deuda_en_gestion_puede_volver_con_poca_mora() throws Exception {
        //  El arrendatario pago agosto en la oficina y Patrimonio manda el
        //  saldo menor. Rechazarlo dejaria a DataBridge cobrando lo que ya no
        //  se debe.
        Debt enGestion = new Debt();
        enGestion.setId(3L);
        enGestion.setCreditor(patrimonio);
        enGestion.setExternalId("CTR-2026-031");
        when(debts.findByCreditorAndExternalId(patrimonio, "CTR-2026-031")).thenReturn(Optional.of(enGestion));

        ResultadoDeuda resultado = recibir(cartera(cargo("Arriendo septiembre", "2026-09", "2026-09-05")));

        assertEquals("actualizada", resultado.resultado());
    }

    // ------------------------------------------------------------------
    //  El mes siguiente: la deuda vuelve en la cartera del acreedor
    // ------------------------------------------------------------------

    private Debt existente(Debt.Status estado) {
        Debt deuda = new Debt();
        deuda.setId(3L);
        deuda.setCreditor(patrimonio);
        deuda.setExternalId("CTR-2026-031");
        deuda.setCurrency(Debt.Currency.CLP);
        deuda.setStatus(estado);
        when(debts.findByCreditorAndExternalId(patrimonio, "CTR-2026-031")).thenReturn(Optional.of(deuda));
        return deuda;
    }

    /** Un convenio sin pagar: una cuota por monto, cada una un mes despues de la anterior. */
    private List<Installment> convenio(Debt deuda, long... montos) {
        List<Installment> todas = new ArrayList<>();
        Installment original = cuota(deuda, 1, LocalDate.of(2026, 9, 5), 820000, null);
        original.setStatus(Installment.Status.void_);
        todas.add(original);
        Repactation plan = new Repactation();
        for (int i = 0; i < montos.length; i++) {
            todas.add(cuota(deuda, i + 2, LocalDate.of(2026, 10, 20).plusMonths(i), montos[i], plan));
        }
        when(installments.findByDebtOrderByNumberAsc(deuda)).thenReturn(todas);
        when(installments.findByDebtAndStatus(deuda, Installment.Status.pending))
                .thenReturn(todas.stream().filter(c -> c.getStatus() == Installment.Status.pending).toList());
        return todas.subList(1, todas.size());
    }

    private static Installment cuota(Debt deuda, int numero, LocalDate vence, long monto, Repactation plan) {
        Installment cuota = new Installment();
        cuota.setId((long) numero);
        cuota.setDebt(deuda);
        cuota.setNumber((short) numero);
        cuota.setDueDate(vence);
        cuota.setAmount(BigDecimal.valueOf(monto));
        cuota.setRepactation(plan);
        return cuota;
    }

    /** Las cuotas nuevas que se guardaron: las que no existian. */
    private List<Installment> cuotasNuevas() {
        ArgumentCaptor<Installment> guardadas = ArgumentCaptor.forClass(Installment.class);
        verify(installments, atLeastOnce()).save(guardadas.capture());
        return guardadas.getAllValues().stream().filter(c -> c.getId() == null).toList();
    }

    @Test
    void un_convenio_sigue_en_pie_y_el_mes_nuevo_queda_aparte() throws Exception {
        //  Valentina acepto 2 cuotas por agosto y septiembre. La cartera de
        //  octubre trae los dos meses y el nuevo.
        Debt deuda = existente(Debt.Status.repacted);
        List<Installment> plan = convenio(deuda, 410000, 410000);

        ResultadoDeuda resultado = recibir(carteraAl("2026-10-18",
                cargo("Arriendo agosto", "2026-08", "2026-08-05"),
                cargo("Arriendo septiembre", "2026-09", "2026-09-05"),
                cargo("Arriendo octubre", "2026-10", "2026-10-05")));

        assertEquals("actualizada", resultado.resultado());
        assertEquals(Debt.Status.repacted, deuda.getStatus());
        assertTrue(plan.stream().allMatch(c -> c.getStatus() == Installment.Status.pending
                && c.getAmount().compareTo(BigDecimal.valueOf(410000)) == 0), "las cuotas del convenio no cambian");
        Installment aparte = cuotasNuevas().getFirst();
        assertEquals(0, aparte.getAmount().compareTo(BigDecimal.valueOf(410000)));
        assertEquals(LocalDate.of(2026, 10, 5), aparte.getDueDate());
        assertEquals((short) 4, aparte.getNumber());
        assertTrue(!aparte.enConvenio(), "el mes nuevo no es parte del convenio");
    }

    @Test
    void lo_que_se_pago_en_la_oficina_se_descuenta_de_las_ultimas_cuotas() throws Exception {
        //  Tres cuotas de 410.000 y el acreedor ahora informa 590.000: pagaron
        //  640.000 en la oficina. Se va la ultima entera y 230.000 de la segunda.
        Debt deuda = existente(Debt.Status.repacted);
        List<Installment> plan = convenio(deuda, 410000, 410000, 410000);

        ResultadoDeuda resultado = recibir(cartera(
                cargo("Arriendo agosto", "2026-08", "2026-08-05", 180000),
                cargo("Arriendo septiembre", "2026-09", "2026-09-05", 410000)));

        assertEquals("actualizada", resultado.resultado());
        assertEquals(Installment.Status.pending, plan.get(0).getStatus());
        assertEquals(0, plan.get(0).getAmount().compareTo(BigDecimal.valueOf(410000)));
        assertEquals(0, plan.get(1).getAmount().compareTo(BigDecimal.valueOf(180000)));
        assertEquals(Installment.Status.void_, plan.get(2).getStatus());
        assertTrue(cuotasNuevas().isEmpty(), "no hay nada aparte que cobrar");
    }

    private void pagadaHastaSeptiembre(Debt deuda) {
        DebtCharge agosto = new DebtCharge();
        agosto.setConcept("Arriendo agosto");
        agosto.setAmount(BigDecimal.valueOf(410000));
        agosto.setDueDate(LocalDate.of(2026, 8, 5));
        DebtCharge septiembre = new DebtCharge();
        septiembre.setConcept("Arriendo septiembre");
        septiembre.setAmount(BigDecimal.valueOf(410000));
        septiembre.setDueDate(LocalDate.of(2026, 9, 5));
        when(charges.findByDebtOrderByDueDateAsc(deuda)).thenReturn(List.of(agosto, septiembre));
    }

    @Test
    void una_deuda_pagada_vuelve_si_el_deudor_se_atrasa_de_nuevo() throws Exception {
        Debt deuda = existente(Debt.Status.paid);
        pagadaHastaSeptiembre(deuda);

        ResultadoDeuda resultado = recibir(carteraAl("2026-11-18",
                cargo("Arriendo octubre", "2026-10", "2026-10-05"),
                cargo("Arriendo noviembre", "2026-11", "2026-11-05")));

        assertEquals("actualizada", resultado.resultado());
        assertEquals(Debt.Status.open, deuda.getStatus());
        assertEquals(0, cuotasNuevas().getFirst().getAmount().compareTo(BigDecimal.valueOf(820000)));
    }

    @Test
    void una_deuda_pagada_no_vuelve_con_lo_que_ya_se_pago() throws Exception {
        //  Septiembre ya se pago: cobrarlo de nuevo seria cobrarlo dos veces.
        Debt deuda = existente(Debt.Status.paid);
        pagadaHastaSeptiembre(deuda);

        ResultadoDeuda resultado = recibir(carteraAl("2026-10-18",
                cargo("Arriendo septiembre", "2026-09", "2026-09-05"),
                cargo("Arriendo octubre", "2026-10", "2026-10-05")));

        assertEquals("rechazada", resultado.resultado());
        assertEquals("deuda_saldada", resultado.errores().getFirst().codigo());
        assertEquals(Debt.Status.paid, deuda.getStatus());
    }

    @Test
    void una_deuda_pagada_que_vuelve_con_poca_mora_todavia_no_entra() throws Exception {
        Debt deuda = existente(Debt.Status.paid);
        pagadaHastaSeptiembre(deuda);

        ResultadoDeuda resultado = recibir(carteraAl("2026-10-18", cargo("Arriendo octubre", "2026-10", "2026-10-05")));

        assertEquals("rechazada", resultado.resultado());
        assertEquals("bajo_umbral_mora", resultado.errores().getFirst().codigo());
    }

    @Test
    void una_deuda_pagada_no_se_retira() throws Exception {
        existente(Debt.Status.paid);

        ResultadoDeuda resultado = recibir(retiro("pago_directo"));

        assertEquals("rechazada", resultado.resultado());
        assertEquals("deuda_saldada", resultado.errores().getFirst().codigo());
    }

    @Test
    void la_agencia_devuelve_un_caso_que_paso_su_mora_maxima() throws Exception {
        Debt deuda = existente(Debt.Status.open);

        ResultadoDeuda resultado = recibir(retiro("fuera_de_mandato"));

        assertEquals("retirada", resultado.resultado());
        assertEquals(Debt.Status.withdrawn, deuda.getStatus());
        assertNull(resultado.errores());
    }

    private JsonNode retiro(String motivo) throws Exception {
        return json.readTree("""
                {"version": "1.0",
                 "lote": {"id_externo": "APX-2026-10-19-005", "fecha_corte": "2026-10-18",
                          "acreedor": {"rut": "76418902-7"}},
                 "deudas": [{"id_externo": "CTR-2026-031", "accion": "retirar", "motivo_retiro": "%s"}]}
                """.formatted(motivo));
    }

    @Test
    void un_umbral_menor_que_uno_no_deja_arrancar_el_servicio() {
        assertThrows(IllegalStateException.class, () -> new CarteraIntakeService(organizations, mandates,
                campaigns, batches, debtors, debts, charges, installments, events, json, eventos, avisos, 0,
                new BigDecimal("3.0")));
    }

    // ------------------------------------------------------------------
    //  Todos los clientes con contrato: el que esta al dia viene sin cargos
    // ------------------------------------------------------------------

    @Test
    void un_cliente_al_dia_se_acepta_sin_guardar_nada() throws Exception {
        CarteraResponse respuesta = servicio.recibir(patrimonio, cartera(), Batch.Source.api);

        ResultadoDeuda resultado = respuesta.resultados().getFirst();
        assertEquals("al_dia", resultado.resultado());
        assertEquals(1, respuesta.aceptadas());
        //  De quien no debe nada no se guarda ni un dato.
        verify(debtors, never()).save(any());
        verify(debts, never()).save(any());
        verify(avisos, never()).publishEvent(any());
    }

    @Test
    void al_dia_con_una_deuda_en_gestion_la_cierra_como_pago_directo() throws Exception {
        Debt deuda = existente(Debt.Status.open);
        Installment pendiente = cuota(deuda, 1, LocalDate.of(2026, 9, 5), 820000, null);
        when(installments.findByDebtAndStatus(deuda, Installment.Status.pending)).thenReturn(List.of(pendiente));

        ResultadoDeuda resultado = recibir(cartera());

        assertEquals("retirada", resultado.resultado());
        assertEquals(Debt.Status.withdrawn, deuda.getStatus());
        verify(events).save(argThat(evento -> "pago_directo".equals(evento.getReference())));
        assertEquals(Installment.Status.void_, pendiente.getStatus());
    }

    @Test
    void al_dia_con_una_deuda_ya_pagada_no_cambia_nada() throws Exception {
        Debt deuda = existente(Debt.Status.paid);

        ResultadoDeuda resultado = recibir(cartera());

        assertEquals("al_dia", resultado.resultado());
        assertEquals(Debt.Status.paid, deuda.getStatus());
        verify(debts, never()).save(any());
    }

    // ------------------------------------------------------------------
    //  La carga registra deudas sin solicitar codigos ni agendar invitaciones.
    // ------------------------------------------------------------------

    @Test
    void una_deuda_que_entra_no_invita_al_deudor() throws Exception {
        recibir(cartera(
                cargo("Arriendo agosto", "2026-08", "2026-08-05"),
                cargo("Arriendo septiembre", "2026-09", "2026-09-05")));

        verify(avisos, never()).publishEvent(any());
    }

    @Test
    void una_deuda_que_ya_estaba_en_gestion_no_vuelve_a_invitar() throws Exception {
        existente(Debt.Status.open);

        recibir(cartera(
                cargo("Arriendo agosto", "2026-08", "2026-08-05"),
                cargo("Arriendo septiembre", "2026-09", "2026-09-05")));

        verify(avisos, never()).publishEvent(any());
    }

    @Test
    void una_deuda_pagada_que_vuelve_a_cobranza_no_invita_de_nuevo() throws Exception {
        existente(Debt.Status.paid);

        ResultadoDeuda resultado = recibir(carteraAl("2026-11-18",
                cargo("Arriendo octubre", "2026-10", "2026-10-05"),
                cargo("Arriendo noviembre", "2026-11", "2026-11-05")));

        assertEquals("actualizada", resultado.resultado());
        verify(avisos, never()).publishEvent(any());
    }

    // ------------------------------------------------------------------
    //  La tasa de interes del acreedor
    // ------------------------------------------------------------------

    private JsonNode conTasa(String tasa) throws Exception {
        JsonNode payload = cartera(cargo("Arriendo agosto", "2026-08", "2026-08-05"));
        ((ObjectNode) payload.get("deudas").get(0)).set("tasa_interes_mensual", json.readTree(tasa));
        return payload;
    }

    @Test
    void la_tasa_que_pacto_el_acreedor_queda_en_la_deuda() throws Exception {
        ResultadoDeuda resultado = recibir(conTasa("1.5"));

        assertEquals("registrada", resultado.resultado());
        ArgumentCaptor<Debt> guardada = ArgumentCaptor.forClass(Debt.class);
        verify(debts, org.mockito.Mockito.atLeastOnce()).save(guardada.capture());
        assertEquals(new BigDecimal("1.5"), guardada.getValue().getInterestRate());
    }

    @Test
    void sin_tasa_la_deuda_no_genera_intereses() throws Exception {
        recibir(cartera(cargo("Arriendo agosto", "2026-08", "2026-08-05")));

        ArgumentCaptor<Debt> guardada = ArgumentCaptor.forClass(Debt.class);
        verify(debts, org.mockito.Mockito.atLeastOnce()).save(guardada.capture());
        assertNull(guardada.getValue().getInterestRate());
    }

    @Test
    void una_tasa_sobre_la_maxima_se_rechaza() throws Exception {
        ResultadoDeuda resultado = recibir(conTasa("3.5"));

        assertEquals("rechazada", resultado.resultado());
        assertEquals("tasa_sobre_maxima", resultado.errores().getFirst().codigo());
        assertEquals("La tasa de 3.5% mensual supera la maxima permitida, 3.0%",
                resultado.errores().getFirst().mensaje());
        verify(debts, never()).save(any());
    }

    @Test
    void una_tasa_que_no_es_un_porcentaje_valido_se_rechaza() throws Exception {
        for (String tasa : List.of("0", "-1", "\"uno y medio\"", "1.555")) {
            ResultadoDeuda resultado = recibir(conTasa(tasa));
            assertEquals("tasa_invalida", resultado.errores().getFirst().codigo(), tasa);
        }
        verify(debts, never()).save(any());
    }

    // ------------------------------------------------------------------
    //  La campana de un acreedor que cobra sin agencia
    // ------------------------------------------------------------------

    private JsonNode conCampana(String campana) throws Exception {
        JsonNode payload = cartera(cargo("Arriendo agosto", "2026-08", "2026-08-05"));
        ((ObjectNode) payload.get("lote")).put("campana_id_externo", campana);
        return payload;
    }

    @Test
    void sin_agencia_el_lote_nombra_la_campana_del_mismo_acreedor() throws Exception {
        Campaign suya = new Campaign();
        suya.setAgency(patrimonio);
        suya.setCreditor(patrimonio);
        when(campaigns.findByAgencyAndExternalId(patrimonio, "PAT-CMP-1")).thenReturn(Optional.of(suya));

        CarteraResponse respuesta = servicio.recibir(patrimonio, conCampana("PAT-CMP-1"), Batch.Source.api);

        assertEquals("registrada", respuesta.resultados().getFirst().resultado());
        assertEquals(List.of(), respuesta.camposIgnorados(), "lote.campana_id_externo es parte del contrato");
        ArgumentCaptor<Debt> guardada = ArgumentCaptor.forClass(Debt.class);
        verify(debts, org.mockito.Mockito.atLeastOnce()).save(guardada.capture());
        assertEquals(suya, guardada.getValue().getCampaign());
    }

    @Test
    void la_campana_de_otro_acreedor_no_sirve() throws Exception {
        Organization otro = new Organization();
        otro.setId(9L);
        Campaign ajena = new Campaign();
        ajena.setAgency(patrimonio);
        ajena.setCreditor(otro);
        when(campaigns.findByAgencyAndExternalId(patrimonio, "AJENA-1")).thenReturn(Optional.of(ajena));

        CarteraInvalida fallo = assertThrows(CarteraInvalida.class,
                () -> servicio.recibir(patrimonio, conCampana("AJENA-1"), Batch.Source.api));

        assertEquals("campana_desconocida", fallo.getCodigo());
    }

    @Test
    void una_campana_que_no_registro_no_sirve() throws Exception {
        when(campaigns.findByAgencyAndExternalId(patrimonio, "NO-EXISTE")).thenReturn(Optional.empty());

        CarteraInvalida fallo = assertThrows(CarteraInvalida.class,
                () -> servicio.recibir(patrimonio, conCampana("NO-EXISTE"), Batch.Source.api));

        assertEquals("campana_desconocida", fallo.getCodigo());
        assertEquals(404, fallo.getStatus());
    }

    // ------------------------------------------------------------------
    //  El correo del deudor (#66)
    // ------------------------------------------------------------------

    /** La cartera de siempre, con otro correo y, si se pide, un telefono. */
    private JsonNode conCorreo(String correo, boolean conTelefono) throws Exception {
        JsonNode payload = cartera(cargo("Arriendo agosto", "2026-08", "2026-08-05"));
        ObjectNode deudor = (ObjectNode) payload.get("deudas").get(0).get("deudor");
        deudor.put("correo", correo);
        if (conTelefono) {
            deudor.put("telefono", "+56987654321");
        }
        return payload;
    }

    private Debtor deudorGuardado() {
        ArgumentCaptor<Debtor> guardado = ArgumentCaptor.forClass(Debtor.class);
        verify(debtors, org.mockito.Mockito.atLeastOnce()).save(guardado.capture());
        return guardado.getValue();
    }

    @Test
    void un_correo_invalido_deja_entrar_la_deuda_sin_correo_y_con_el_aviso() throws Exception {
        for (String malo : new String[]{"juan@gmail", "juan perez@gmail.com", "@gmail.com"}) {
            ResultadoDeuda resultado = recibir(conCorreo(malo, true));

            assertEquals("registrada", resultado.resultado(), malo);
            assertEquals("correo_invalido", resultado.avisos().getFirst().codigo(), malo);
            assertNull(deudorGuardado().getEmail(), malo);
        }
    }

    @Test
    void un_correo_invalido_sin_telefono_se_rechaza_y_dice_por_que() throws Exception {
        ResultadoDeuda resultado = recibir(conCorreo("juan@gmail", false));

        assertEquals("rechazada", resultado.resultado());
        assertEquals("sin_canal_contacto", resultado.errores().getFirst().codigo());
        assertEquals("El deudor no trae un correo valido ni telefono", resultado.errores().getFirst().mensaje());
        assertEquals("correo_invalido", resultado.avisos().getFirst().codigo());
    }

    @Test
    void un_correo_valido_se_guarda_sin_espacios_y_con_el_dominio_en_minusculas() throws Exception {
        for (String bueno : new String[]{"juan.perez+arriendo@gmail.com", "ana@sub.dominio.cl", "x@empresa.app"}) {
            ResultadoDeuda resultado = recibir(conCorreo(bueno, false));
            assertEquals("registrada", resultado.resultado(), bueno);
            assertNull(resultado.avisos(), bueno);
            assertEquals(bueno, deudorGuardado().getEmail());
        }
        recibir(conCorreo("  Valentina.Soto@CORREO.cl ", false));
        assertEquals("Valentina.Soto@correo.cl", deudorGuardado().getEmail());
    }

    @Test
    void un_correo_invalido_no_borra_el_bueno_que_el_deudor_ya_tenia() throws Exception {
        Debtor valentina = new Debtor();
        valentina.setRut("18905214-6");
        valentina.setEmail("valentina.soto@correo.cl");
        when(debtors.findByRut("18905214-6")).thenReturn(Optional.of(valentina));

        ResultadoDeuda resultado = recibir(conCorreo("valentina@correo", true));

        assertEquals("correo_invalido", resultado.avisos().getFirst().codigo());
        assertEquals("valentina.soto@correo.cl", deudorGuardado().getEmail());
    }
}
