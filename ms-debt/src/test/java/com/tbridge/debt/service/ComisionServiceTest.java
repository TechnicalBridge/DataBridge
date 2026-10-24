package com.tbridge.debt.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.debt.dto.response.ComisionResponse;
import com.tbridge.debt.dto.response.ComisionesResponse;
import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.model.Batch;
import com.tbridge.debt.model.Campaign;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.DebtEvent;
import com.tbridge.debt.model.DetallePago;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.DebtEventRepository;
import com.tbridge.debt.security.JwtPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** La comision de un mes: lo recuperado en pesos por quien cargo la deuda, su tramo y lo de cada campana. */
class ComisionServiceTest {

    private static final YearMonth OCTUBRE = YearMonth.of(2026, 10);

    private final DebtEventRepository eventos = mock(DebtEventRepository.class);
    private final DebtService debts = mock(DebtService.class);
    private final ComisionService servicio = new ComisionService(eventos, debts, new ObjectMapper());
    private final JwtPrincipal camila = new JwtPrincipal("1", "camila@apofyx.cl", "CREDITOR", "Camila", "77305118-6");
    private Organization apofyx;
    private Organization patrimonio;
    private Campaign arriendos;
    private Campaign aranceles;

    @BeforeEach
    void preparar() {
        apofyx = empresa("77305118-6", "APOFYX");
        patrimonio = empresa("76418902-7", "Patrimonio Inmuebles");
        arriendos = campana("CMP-1", "Arriendos octubre");
        aranceles = campana("CMP-2", "Aranceles");
        when(debts.organizacionDe(camila)).thenReturn(apofyx);
    }

    private static Organization empresa(String rut, String nombre) {
        Organization o = new Organization();
        o.setRut(rut);
        o.setTradeName(nombre);
        return o;
    }

    private static Campaign campana(String id, String nombre) {
        Campaign c = new Campaign();
        c.setExternalId(id);
        c.setName(nombre);
        return c;
    }

    /** Un pago abonado: cargado por esa empresa, en esa campana, con lo que dice su detalle. */
    private static DebtEvent pago(Organization cargo, Campaign campana, String monto, Debt.Currency moneda,
                                  String detalle) {
        Batch lote = new Batch();
        lote.setSender(cargo);
        Debt deuda = new Debt();
        deuda.setLastBatch(lote);
        deuda.setCampaign(campana);
        deuda.setCurrency(moneda);
        DebtEvent evento = DebtEvent.de(deuda, DebtEvent.Type.payment_applied, DebtEvent.Actor.system)
                .conMonto(new BigDecimal(monto), moneda)
                .conReferencia("pago-" + monto)
                .conDetalle(detalle);
        evento.setOccurredAt(Instant.parse("2026-10-06T15:30:00Z"));
        return evento;
    }

    @Test
    void lo_recuperado_en_el_mes_decide_el_tramo_y_cada_campana_muestra_su_parte() {
        when(eventos.entre(eq(DebtEvent.Type.payment_applied), any(), any())).thenReturn(List.of(
                pago(apofyx, arriendos, "350000", Debt.Currency.CLP, "{\"pagoId\":1,\"montoClp\":350000}"),
                pago(apofyx, arriendos, "350000", Debt.Currency.CLP, "{\"pagoId\":2,\"montoClp\":350000}"),
                //  En UF cuenta por los pesos que cobro la pasarela.
                pago(apofyx, aranceles, "12.5", Debt.Currency.UF, "{\"pagoId\":3,\"montoClp\":493571,\"valorUf\":39485.65}")));

        ComisionResponse octubre = servicio.deLaEmpresa(camila, OCTUBRE);

        assertEquals(LocalDate.of(2026, 10, 1), octubre.mes());
        assertEquals(1_193_571, octubre.recuperado());
        assertEquals(3, octubre.pagos());
        assertEquals(new BigDecimal("10"), octubre.porcentaje(), "pasa del millon: el 10 % va sobre todo G");
        assertEquals(119_357, octubre.comision());
        assertEquals(1_074_214, octubre.paraLaEmpresa());
        assertEquals("CMP-1", octubre.porCampana().get(0).idExterno());
        assertEquals(700_000, octubre.porCampana().get(0).recuperado());
        assertEquals(2, octubre.porCampana().get(0).pagos());
        assertEquals(493_571, octubre.porCampana().get(1).recuperado());
        assertEquals(List.of(false, true, false, false), octubre.tramos().stream().map(ComisionResponse.TramoResponse::vigente).toList());
    }

    @Test
    void el_mes_va_de_la_medianoche_del_dia_1_a_la_del_mes_siguiente_en_chile() {
        servicio.deLaEmpresa(camila, OCTUBRE);

        ArgumentCaptor<Instant> desde = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> hasta = ArgumentCaptor.forClass(Instant.class);
        verify(eventos).entre(eq(DebtEvent.Type.payment_applied), desde.capture(),
                hasta.capture());
        assertEquals(LocalDateTime.of(2026, 10, 1, 0, 0).atZone(ComisionService.CHILE).toInstant(), desde.getValue());
        assertEquals(LocalDateTime.of(2026, 11, 1, 0, 0).atZone(ComisionService.CHILE).toInstant(), hasta.getValue());
    }

    @Test
    void sin_los_pesos_en_el_detalle_usa_el_monto_en_clp_o_el_de_uf_por_su_valor() {
        assertEquals(185_000, servicio.enPesos(pago(apofyx, null, "185000", Debt.Currency.CLP, null)));
        assertEquals(493_571, servicio.enPesos(pago(apofyx, null, "12.5", Debt.Currency.UF,
                "{\"valorUf\":39485.65}")));
        assertEquals(0, servicio.enPesos(pago(apofyx, null, "12.5", Debt.Currency.UF, "{}")),
                "en UF y sin saber los pesos, no inventa un monto");
    }

    @Test
    void un_detalle_antiguo_nulo_o_mal_formado_no_impide_calcular_un_pago_en_pesos() {
        for (String detalle : List.of("null", "[]", "42", "{incompleto")) {
            assertEquals(185_000, servicio.enPesos(pago(apofyx, null, "185000", Debt.Currency.CLP, detalle)));
        }
    }

    @Test
    void un_mes_sin_pagos_no_tiene_tramo_ni_comision_y_un_pago_sin_campana_va_aparte() {
        ComisionResponse vacio = servicio.deLaEmpresa(camila, OCTUBRE);
        assertEquals(0, vacio.recuperado());
        assertEquals(BigDecimal.ZERO, vacio.porcentaje());
        assertTrue(vacio.tramos().stream().noneMatch(ComisionResponse.TramoResponse::vigente));

        when(eventos.entre(any(), any(), any())).thenReturn(List.of(
                pago(apofyx, null, "800000", Debt.Currency.CLP, "{\"montoClp\":800000}")));
        ComisionResponse sinCampana = servicio.deLaEmpresa(camila, OCTUBRE);
        assertNull(sinCampana.porCampana().get(0).idExterno());
        assertEquals("Sin campaña", sinCampana.porCampana().get(0).nombre());
        assertEquals(48_000, sinCampana.comision());
        assertEquals(752_000, sinCampana.paraLaEmpresa());
    }

    @Test
    void solo_una_empresa_ve_lo_que_le_paga_a_databridge() {
        JwtPrincipal deudor = new JwtPrincipal("16482337-7", null, "DEBTOR", "Felipe", null);
        ApiException fallo = assertThrows(ApiException.class, () -> servicio.deLaEmpresa(deudor, OCTUBRE));
        assertEquals(403, fallo.getStatus().value());
    }

    @Test
    void el_admin_ve_cada_empresa_y_cada_pago_lo_paga_quien_cargo_la_deuda() {
        when(eventos.entre(eq(DebtEvent.Type.payment_applied), any(), any())).thenReturn(List.of(
                pago(apofyx, arriendos, "6000000", Debt.Currency.CLP, "{\"montoClp\":6000000}"),
                pago(patrimonio, null, "500000", Debt.Currency.CLP, "{\"montoClp\":500000}"),
                pago(apofyx, aranceles, "100000", Debt.Currency.CLP, "{\"montoClp\":100000}")));

        ComisionesResponse octubre = servicio.deTodas(OCTUBRE);

        assertEquals(2, octubre.empresas().size());
        ComisionesResponse.Empresa primera = octubre.empresas().get(0);
        assertEquals("APOFYX", primera.nombre(), "de la que mas paga a la que menos");
        assertEquals(6_100_000, primera.recuperado());
        assertEquals(new BigDecimal("12"), primera.porcentaje());
        assertEquals(732_000, primera.comision());
        assertEquals(2, primera.pagos());
        ComisionesResponse.Empresa segunda = octubre.empresas().get(1);
        assertEquals("76418902-7", segunda.rut());
        assertEquals(30_000, segunda.comision());
        assertEquals(6_600_000, octubre.recuperado());
        assertEquals(762_000, octubre.comision());
    }

    @Test
    void lee_los_pesos_del_detalle_real_serializado_en_snake_case() throws Exception {
        String detalle = new ObjectMapper().writeValueAsString(new DetallePago(3L, 493_570L,
                new BigDecimal("39485.65"), "webpay", List.of(1), 1, null, null));
        assertEquals(493_570, servicio.enPesos(pago(apofyx, aranceles, "12.5", Debt.Currency.UF, detalle)),
                "usa los pesos cobrados, no vuelve a calcularlos con otro redondeo");
    }

    @Test
    void sin_pesos_lee_el_valor_uf_del_detalle_real() throws Exception {
        String detalle = new ObjectMapper().writeValueAsString(new DetallePago(3L, null,
                new BigDecimal("39485.65"), "webpay", List.of(1), 1, null, null));
        assertEquals(493_571, servicio.enPesos(pago(apofyx, aranceles, "12.5", Debt.Currency.UF, detalle)));
    }

    @Test
    void recargar_la_deuda_no_reasigna_el_pago_a_otra_empresa_ni_campana() throws Exception {
        String detalle = new ObjectMapper().writeValueAsString(new DetallePago(3L, 800_000L, null,
                "webpay", List.of(1), 1, null, null, null,
                apofyx.getRut(), apofyx.getTradeName(), arriendos.getExternalId(), arriendos.getName()));
        // La deuda ya fue cargada por otra empresa y en otra campana despues del pago.
        DebtEvent anterior = pago(patrimonio, aranceles, "800000", Debt.Currency.CLP, detalle);
        when(eventos.entre(any(), any(), any())).thenReturn(List.of(anterior));
        JwtPrincipal otra = new JwtPrincipal("2", null, "CREDITOR", "Otra", patrimonio.getRut());
        when(debts.organizacionDe(otra)).thenReturn(patrimonio);

        ComisionResponse original = servicio.deLaEmpresa(camila, OCTUBRE);
        assertEquals(800_000, original.recuperado());
        assertEquals(arriendos.getExternalId(), original.porCampana().getFirst().idExterno());
        assertEquals(arriendos.getName(), original.porCampana().getFirst().nombre());
        assertEquals(0, servicio.deLaEmpresa(otra, OCTUBRE).recuperado());
        ComisionesResponse admin = servicio.deTodas(OCTUBRE);
        assertEquals(1, admin.empresas().size());
        assertEquals(apofyx.getRut(), admin.empresas().getFirst().rut());
        assertEquals(48_000, admin.comision());
    }

    @Test
    void un_pago_sin_campana_no_adquiere_una_campana_posterior() throws Exception {
        String detalle = new ObjectMapper().writeValueAsString(new DetallePago(3L, 800_000L, null,
                "webpay", List.of(1), 1, null, null, null,
                apofyx.getRut(), apofyx.getTradeName(), null, null));
        when(eventos.entre(any(), any(), any())).thenReturn(List.of(
                pago(apofyx, aranceles, "800000", Debt.Currency.CLP, detalle)));
        ComisionResponse original = servicio.deLaEmpresa(camila, OCTUBRE);
        assertNull(original.porCampana().getFirst().idExterno());
        assertEquals("Sin campaña", original.porCampana().getFirst().nombre());
    }
}
