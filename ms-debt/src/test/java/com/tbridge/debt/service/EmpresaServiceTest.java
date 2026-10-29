package com.tbridge.debt.service;

import com.tbridge.debt.dto.request.NuevaEmpresaRequest;
import com.tbridge.debt.dto.response.AccionResponse;
import com.tbridge.debt.dto.response.EmpresaResponse;
import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.model.AdminLog;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.Mandate;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.AdminLogRepository;
import com.tbridge.debt.repository.ConteoDeDeudas;
import com.tbridge.debt.repository.DebtRepository;
import com.tbridge.debt.repository.MandateRepository;
import com.tbridge.debt.repository.OrganizationRepository;
import com.tbridge.debt.repository.SaldoPendiente;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Las empresas en el panel de administracion: el admin de DataBridge ve
 * totales de cada cartera y da de alta empresas usuarias, y cada alta queda
 * anotada.
 */
class EmpresaServiceTest {

    private static final String ADMIN = "admin@databridge.cl";

    private OrganizationRepository organizations;
    private MandateRepository mandates;
    private DebtRepository debts;
    private AdminLogRepository bitacora;
    private EmpresaService servicio;

    private final Organization patrimonio = empresa(1L, "76418902-7", "Patrimonio Inmuebles", Organization.Kind.creditor);
    private final Organization apofyx = empresa(2L, "77305118-6", "APOFYX", Organization.Kind.agency);
    private final Organization directa = empresa(3L, "76543210-3", "Clinica Andes", Organization.Kind.creditor);

    @BeforeEach
    void preparar() {
        organizations = mock(OrganizationRepository.class);
        mandates = mock(MandateRepository.class);
        debts = mock(DebtRepository.class);
        bitacora = mock(AdminLogRepository.class);
        servicio = new EmpresaService(organizations, mandates, debts, bitacora);

        when(organizations.findAll(any(Sort.class))).thenReturn(List.of(apofyx, directa, patrimonio));
        for (Organization empresa : List.of(patrimonio, apofyx, directa)) {
            when(organizations.findByRut(empresa.getRut())).thenReturn(Optional.of(empresa));
        }
        when(organizations.saveAndFlush(any())).thenAnswer(llamada -> llamada.getArgument(0));
        when(mandates.todosConSusEmpresas()).thenReturn(List.of());
        when(debts.contarPorEmpresa()).thenReturn(List.of());
        when(debts.saldoPorEmpresa()).thenReturn(List.of());
    }

    private static Organization empresa(Long id, String rut, String nombre, Organization.Kind tipo) {
        Organization empresa = new Organization();
        empresa.setId(id);
        empresa.setRut(rut);
        empresa.setLegalName(nombre + " SpA");
        empresa.setTradeName(nombre);
        empresa.setKind(tipo);
        return empresa;
    }

    private EmpresaResponse de(String rut) {
        return servicio.listar().stream().filter(e -> e.rut().equals(rut)).findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------
    //  Los totales
    // ------------------------------------------------------------------

    @Test
    void una_deuda_cuenta_para_su_acreedor_y_para_la_agencia_que_la_entrego() {
        //  Patrimonio es el acreedor y APOFYX la entrego: es de la cartera de los dos.
        when(debts.contarPorEmpresa()).thenReturn(List.of(new ConteoDeDeudas(1L, 2L, Debt.Status.open, 4)));
        when(debts.saldoPorEmpresa()).thenReturn(List.of(
                new SaldoPendiente(1L, 2L, Debt.Currency.CLP, new BigDecimal("3200000"))));

        for (String rut : List.of(patrimonio.getRut(), apofyx.getRut())) {
            EmpresaResponse empresa = de(rut);
            assertEquals(4, empresa.deudas());
            assertEquals(4, empresa.enGestion());
            assertEquals(List.of(new EmpresaResponse.Monto("CLP", new BigDecimal("3200000"))), empresa.porCobrar());
        }
        assertEquals(0, de(directa.getRut()).deudas(), "la cartera de otra empresa no se mezcla");
        assertTrue(de(directa.getRut()).porCobrar().isEmpty());
    }

    @Test
    void lo_que_un_acreedor_entrego_el_mismo_cuenta_una_sola_vez() {
        when(debts.contarPorEmpresa()).thenReturn(List.of(new ConteoDeDeudas(3L, 3L, Debt.Status.open, 5)));
        when(debts.saldoPorEmpresa()).thenReturn(List.of(
                new SaldoPendiente(3L, 3L, Debt.Currency.CLP, new BigDecimal("900000"))));

        EmpresaResponse empresa = de(directa.getRut());
        assertEquals(5, empresa.deudas());
        assertEquals(new BigDecimal("900000"), empresa.porCobrar().getFirst().monto());
    }

    @Test
    void las_pagadas_y_las_retiradas_no_estan_en_gestion() {
        when(debts.contarPorEmpresa()).thenReturn(List.of(
                new ConteoDeDeudas(3L, 3L, Debt.Status.open, 2),
                new ConteoDeDeudas(3L, 3L, Debt.Status.repacted, 1),
                new ConteoDeDeudas(3L, 3L, Debt.Status.disputed, 1),
                new ConteoDeDeudas(3L, 3L, Debt.Status.paid, 6),
                new ConteoDeDeudas(3L, 3L, Debt.Status.withdrawn, 3)));

        EmpresaResponse empresa = de(directa.getRut());
        assertEquals(13, empresa.deudas());
        assertEquals(4, empresa.enGestion());
    }

    @Test
    void los_pesos_y_las_uf_no_se_suman_entre_si() {
        //  APOFYX cobra para dos acreedores: su total junta lo de ambos, cada moneda por su lado.
        when(debts.saldoPorEmpresa()).thenReturn(List.of(
                new SaldoPendiente(1L, 2L, Debt.Currency.UF, new BigDecimal("120.50")),
                new SaldoPendiente(1L, 2L, Debt.Currency.CLP, new BigDecimal("1000000")),
                new SaldoPendiente(3L, 2L, Debt.Currency.CLP, new BigDecimal("250000"))));

        assertEquals(List.of(
                new EmpresaResponse.Monto("CLP", new BigDecimal("1250000")),
                new EmpresaResponse.Monto("UF", new BigDecimal("120.50"))), de(apofyx.getRut()).porCobrar());
    }

    @Test
    void el_mandato_se_ve_desde_cada_lado() {
        Mandate mandato = new Mandate();
        mandato.setAgency(apofyx);
        mandato.setCreditor(patrimonio);
        mandato.setValidFrom(LocalDate.of(2026, 1, 1));
        when(mandates.todosConSusEmpresas()).thenReturn(List.of(mandato));

        EmpresaResponse.Mandato deLaAgencia = de(apofyx.getRut()).mandatos().getFirst();
        assertEquals("cobra_para", deLaAgencia.papel());
        assertEquals(patrimonio.getRut(), deLaAgencia.rut());
        assertTrue(deLaAgencia.vigente());

        EmpresaResponse.Mandato delAcreedor = de(patrimonio.getRut()).mandatos().getFirst();
        assertEquals("le_cobra", delAcreedor.papel());
        assertEquals("APOFYX", delAcreedor.nombre());

        assertTrue(de(directa.getRut()).mandatos().isEmpty());
    }

    @Test
    void un_mandato_terminado_se_muestra_como_no_vigente() {
        Mandate mandato = new Mandate();
        mandato.setAgency(apofyx);
        mandato.setCreditor(patrimonio);
        mandato.setValidFrom(LocalDate.of(2025, 1, 1));
        mandato.setValidTo(LocalDate.of(2025, 12, 31));
        when(mandates.todosConSusEmpresas()).thenReturn(List.of(mandato));

        assertFalse(de(apofyx.getRut()).mandatos().getFirst().vigente());
    }

    @Test
    void la_ficha_de_un_rut_que_no_existe_es_404() {
        when(organizations.findByRut("11111111-1")).thenReturn(Optional.empty());

        ApiException error = assertThrows(ApiException.class, () -> servicio.ficha("11.111.111-1"));
        assertEquals(HttpStatus.NOT_FOUND, error.getStatus());
    }

    // ------------------------------------------------------------------
    //  Dar de alta
    // ------------------------------------------------------------------

    @Test
    void dar_de_alta_guarda_la_empresa_con_el_rut_en_su_forma_canonica_y_anota_quien() {
        when(organizations.findByRut("76086428-5")).thenReturn(Optional.empty());

        EmpresaResponse creada = servicio.crear(
                new NuevaEmpresaRequest("76.086.428-5", " Empresa Test SpA ", "Empresa Test", "agencia"), ADMIN);

        ArgumentCaptor<Organization> guardada = ArgumentCaptor.forClass(Organization.class);
        verify(organizations).saveAndFlush(guardada.capture());
        assertEquals("76086428-5", guardada.getValue().getRut());
        assertEquals("Empresa Test SpA", guardada.getValue().getLegalName());
        assertEquals("Empresa Test", guardada.getValue().getTradeName());
        assertEquals(Organization.Kind.agency, guardada.getValue().getKind());
        assertEquals(Organization.Status.active, guardada.getValue().getStatus());

        assertEquals("agencia", creada.tipo());
        assertEquals(0, creada.deudas(), "una empresa nueva parte con la cartera vacia");

        ArgumentCaptor<AdminLog> anotado = ArgumentCaptor.forClass(AdminLog.class);
        verify(bitacora).save(anotado.capture());
        assertEquals(ADMIN, anotado.getValue().getAdminEmail());
        assertEquals(AdminLog.Action.empresa_creada, anotado.getValue().getAction());
        assertEquals("76086428-5", anotado.getValue().getOrgRut());
        assertEquals("Empresa Test", anotado.getValue().getSubject());
    }

    @Test
    void un_acreedor_que_cobra_directo_queda_como_acreedor_y_sin_nombre_usa_su_razon_social() {
        when(organizations.findByRut("76086428-5")).thenReturn(Optional.empty());

        EmpresaResponse creada = servicio.crear(
                new NuevaEmpresaRequest("760864285", "Empresa Test SpA", "  ", "acreedor"), ADMIN);

        assertEquals("acreedor", creada.tipo());
        assertEquals("Empresa Test SpA", creada.nombre());
    }

    @Test
    void un_rut_con_el_digito_verificador_malo_se_rechaza_con_el_motivo() {
        ApiException error = assertThrows(ApiException.class, () -> servicio.crear(
                new NuevaEmpresaRequest("76086428-9", "Empresa Test SpA", null, "agencia"), ADMIN));

        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
        assertTrue(error.getMessage().contains("digito verificador"));
        verify(organizations, never()).saveAndFlush(any());
        verify(bitacora, never()).save(any());
    }

    @Test
    void un_rut_que_ya_existe_se_rechaza_diciendo_de_quien_es() {
        ApiException error = assertThrows(ApiException.class, () -> servicio.crear(
                new NuevaEmpresaRequest("77.305.118-6", "Otra SpA", null, "agencia"), ADMIN));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertTrue(error.getMessage().contains("APOFYX"));
        verify(organizations, never()).saveAndFlush(any());
        verify(bitacora, never()).save(any());
    }

    @Test
    void dos_altas_del_mismo_rut_a_la_vez_dejan_pasar_una_sola() {
        when(organizations.findByRut("76086428-5")).thenReturn(Optional.empty());
        when(organizations.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException("uq_organization_rut"));

        ApiException error = assertThrows(ApiException.class, () -> servicio.crear(
                new NuevaEmpresaRequest("76086428-5", "Empresa Test SpA", null, "agencia"), ADMIN));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        verify(bitacora, never()).save(any());
    }

    // ------------------------------------------------------------------
    //  La bitacora, y lo que pregunta ms-auth
    // ------------------------------------------------------------------

    @Test
    void el_historial_dice_que_se_hizo_quien_y_cuando() {
        AdminLog alta = AdminLog.de(ADMIN, AdminLog.Action.empresa_creada, directa.getRut(), "Clinica Andes");
        when(bitacora.findByOrgRutOrderByOccurredAtDesc(directa.getRut())).thenReturn(List.of(alta));

        List<AccionResponse> historial = servicio.historial("76.543.210-3");

        assertEquals(1, historial.size());
        assertEquals("empresa_creada", historial.getFirst().accion());
        assertEquals(ADMIN, historial.getFirst().quien());
        assertEquals(alta.getOccurredAt(), historial.getFirst().cuando());
    }

    @Test
    void a_ms_auth_se_le_dice_si_la_empresa_existe() {
        when(organizations.findByRut("11111111-1")).thenReturn(Optional.empty());

        assertEquals("APOFYX", servicio.buscar("77.305.118-6").orElseThrow().nombre());
        assertTrue(servicio.buscar("77305118-6").orElseThrow().activa());
        assertTrue(servicio.buscar("11111111-1").isEmpty());
    }

    @Test
    void suspender_y_reactivar_una_empresa() {
        EmpresaResponse suspendida = servicio.suspender(apofyx.getRut(), ADMIN);
        assertFalse(suspendida.activa());
        assertEquals(Organization.Status.suspended, apofyx.getStatus());
        verify(organizations).save(apofyx);
        verify(bitacora).save(any(AdminLog.class));

        EmpresaResponse reactivada = servicio.reactivar(apofyx.getRut(), ADMIN);
        assertTrue(reactivada.activa());
        assertEquals(Organization.Status.active, apofyx.getStatus());
    }
}
