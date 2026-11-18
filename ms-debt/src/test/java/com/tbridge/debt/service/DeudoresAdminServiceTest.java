package com.tbridge.debt.service;

import com.tbridge.debt.dto.response.DebtSummaryResponse;
import com.tbridge.debt.dto.response.DeudorEncontradoResponse;
import com.tbridge.debt.dto.response.FichaDeudorResponse;
import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.model.AdminLog;
import com.tbridge.debt.model.Campaign;
import com.tbridge.debt.model.Contact;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.Debtor;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.AdminLogRepository;
import com.tbridge.debt.repository.ContactRepository;
import com.tbridge.debt.repository.DebtRepository;
import com.tbridge.debt.repository.DebtorRepository;
import com.tbridge.debt.repository.DeudasDelDeudor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Buscar a un deudor desde el panel y abrir su ficha (#434). */
class DeudoresAdminServiceTest {

    private final DebtorRepository deudores = mock(DebtorRepository.class);
    private final DebtRepository deudas = mock(DebtRepository.class);
    private final ContactRepository contactos = mock(ContactRepository.class);
    private final AdminLogRepository bitacora = mock(AdminLogRepository.class);
    private final DebtService resumenes = mock(DebtService.class);
    private final DeudoresAdminService servicio =
            new DeudoresAdminService(deudores, deudas, contactos, bitacora, resumenes);

    private final Organization patrimonio = new Organization();
    private final Organization apofyx = new Organization();
    private final Debtor camila = new Debtor();

    @BeforeEach
    void datos() {
        patrimonio.setId(1L);
        patrimonio.setRut("76418902-7");
        patrimonio.setTradeName("Patrimonio Inmuebles");
        apofyx.setId(2L);
        apofyx.setRut("77305118-6");
        apofyx.setTradeName("APOFYX");
        camila.setId(10L);
        camila.setRut("16482337-7");
        camila.setFullName("Camila Fuentes Rojas");
        camila.setEmail("camila@example.com");
    }

    @Test
    void con_menos_de_tres_letras_no_busca() {
        ApiException error = assertThrows(ApiException.class, () -> servicio.buscar(" 16 ", 0, 20));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        verify(deudores, never()).buscar(any(), any(), any());
    }

    @Test
    void un_rut_con_puntos_y_guion_se_busca_por_su_comienzo_sin_guion() {
        when(deudores.buscar(any(), any(), any())).thenReturn(Page.empty());
        servicio.buscar("16.482.337-7", 0, 20);
        verify(deudores).buscar(eq("164823377"), eq("16.482.337-7"), any(Pageable.class));
        assertEquals("16482", DeudoresAdminService.comoRut("16.482"));
        assertEquals("16482337K", DeudoresAdminService.comoRut("16.482.337-k"));
        assertNull(DeudoresAdminService.comoRut("Camila"));
    }

    @Test
    void un_porcentaje_o_un_guion_bajo_se_buscan_tal_cual() {
        assertEquals("100!% real!_ya!!", DeudoresAdminService.escapar("100% real_ya!"));
    }

    @Test
    void cada_encontrado_dice_cuantas_deudas_tiene_y_con_quien() {
        when(deudores.buscar(any(), any(), any())).thenReturn(new PageImpl<>(List.of(camila)));
        when(deudas.porDeudorYAcreedor(List.of(camila))).thenReturn(List.of(
                new DeudasDelDeudor(10L, "Patrimonio Inmuebles", 2), new DeudasDelDeudor(10L, "Instituto Andes", 1)));

        DeudorEncontradoResponse encontrado = servicio.buscar("camila", 0, 20).getContent().getFirst();

        assertEquals("16482337-7", encontrado.rut());
        assertEquals(3, encontrado.deudas());
        assertEquals(List.of("Instituto Andes", "Patrimonio Inmuebles"), encontrado.empresas());
    }

    @Test
    void la_pagina_se_corta_en_cincuenta() {
        ArgumentCaptor<Pageable> pagina = ArgumentCaptor.forClass(Pageable.class);
        when(deudores.buscar(any(), any(), pagina.capture())).thenReturn(Page.empty());
        servicio.buscar("camila", 0, 500);
        assertEquals(50, pagina.getValue().getPageSize());
        assertThrows(ApiException.class, () -> servicio.buscar("camila", -1, 20));
    }

    @Test
    void la_ficha_trae_sus_deudas_con_campana_y_quien_cobra_y_queda_en_la_bitacora() {
        Campaign campana = new Campaign();
        campana.setName("Arriendos septiembre");
        campana.setAgency(apofyx);
        Debt conCampana = deuda(3L, "CTR-007", campana);
        Debt directa = deuda(4L, "CTR-008", null);
        when(deudores.findByRut("16482337-7")).thenReturn(Optional.of(camila));
        when(deudas.findByDebtorOrderByUpdatedAtDesc(camila)).thenReturn(List.of(conCampana, directa));
        when(resumenes.resumenes(List.of(conCampana, directa)))
                .thenReturn(Map.of(3L, resumen(3L, "CTR-007"), 4L, resumen(4L, "CTR-008")));
        when(contactos.findTop50ByDebtDebtorOrderByOccurredAtDesc(camila))
                .thenReturn(List.of(Contact.toque(conCampana, campana, 2, "c***@example.com")));

        FichaDeudorResponse ficha = servicio.ficha("16.482.337-7", "admin@databridge.cl");

        assertEquals("Camila Fuentes Rojas", ficha.nombre());
        assertEquals("APOFYX", ficha.deudas().get(0).cobra());
        assertEquals("Arriendos septiembre", ficha.deudas().get(0).campana());
        assertEquals("Patrimonio Inmuebles", ficha.deudas().get(1).cobra());
        assertNull(ficha.deudas().get(1).campana());
        assertEquals(new BigDecimal("96.25"), ficha.deudas().get(0).saldo());
        assertEquals("c***@example.com", ficha.correosEnviados().getFirst().destino());
        assertEquals("CTR-007", ficha.correosEnviados().getFirst().referencia());
        assertFalse(ficha.estadoDelCorreo().yaNoSeLeEscribe());

        ArgumentCaptor<AdminLog> registro = ArgumentCaptor.forClass(AdminLog.class);
        verify(bitacora).save(registro.capture());
        assertEquals(AdminLog.Action.deudor_consultado, registro.getValue().getAction());
        assertEquals("16482337-7", registro.getValue().getSubject());
        assertEquals("admin@databridge.cl", registro.getValue().getAdminEmail());
        assertNull(registro.getValue().getOrgRut());
    }

    @Test
    void un_rut_invalido_o_un_deudor_que_no_existe_no_quedan_en_la_bitacora() {
        assertEquals(HttpStatus.BAD_REQUEST,
                assertThrows(ApiException.class, () -> servicio.ficha("16482337-1", "a@b.cl")).getStatus());
        when(deudores.findByRut(anyString())).thenReturn(Optional.empty());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ApiException.class, () -> servicio.ficha("16482337-7", "a@b.cl")).getStatus());
        verify(bitacora, never()).save(any());
    }

    private Debt deuda(Long id, String referencia, Campaign campana) {
        Debt deuda = new Debt();
        deuda.setId(id);
        deuda.setExternalId(referencia);
        deuda.setCreditor(patrimonio);
        deuda.setDebtor(camila);
        deuda.setCampaign(campana);
        return deuda;
    }

    private static DebtSummaryResponse resumen(Long id, String referencia) {
        return new DebtSummaryResponse(id, referencia, "Patrimonio Inmuebles", "76418902-7",
                "Camila Fuentes Rojas", "16482337-7", "Arriendo", Debt.Currency.UF,
                new BigDecimal("115.50"), new BigDecimal("96.25"), new BigDecimal("19.25"), Debt.Status.repacted,
                Instant.parse("2026-11-01T12:00:00Z"), 1, 6, true, 0, null, null, null, BigDecimal.ZERO,
                new BigDecimal("96.25"), null, null, false, null, null, false);
    }
}
