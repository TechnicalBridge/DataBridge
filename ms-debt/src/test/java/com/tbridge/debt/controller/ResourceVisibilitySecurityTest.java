package com.tbridge.debt.controller;

import com.tbridge.debt.assembler.DebtModelAssembler;
import com.tbridge.debt.config.SecurityConfig;
import com.tbridge.debt.model.*;
import com.tbridge.debt.repository.*;
import com.tbridge.debt.security.JwtTestConfig;
import com.tbridge.debt.security.JwtTestIssuer;
import com.tbridge.debt.service.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** JWT y autorización reales; únicamente la persistencia y los colaboradores se simulan. */
@WebMvcTest({DebtController.class, InternalController.class, HistorialController.class})
@Import({SecurityConfig.class, JwtTestConfig.class, DebtModelAssembler.class, DebtService.class,
        HistorialService.class, ComprobanteService.class, CertificateService.class})
@ActiveProfiles("test")
class ResourceVisibilitySecurityTest {
    @Autowired MockMvc mvc;
    @Autowired JwtTestIssuer jwt;
    @Autowired DebtService service;
    @Autowired HistorialService historial;
    @MockitoBean DebtRepository debts;
    @MockitoBean DebtorRepository debtors;
    @MockitoBean OrganizationRepository organizations;
    @MockitoBean DebtChargeRepository charges;
    @MockitoBean InstallmentRepository installments;
    @MockitoBean RepactationRepository repactations;
    @MockitoBean DebtEventRepository events;
    @MockitoBean ContactRepository contacts;
    @MockitoBean RepactationService repactation;
    @MockitoBean EventosService eventos;
    @MockitoBean DescuentoService descuentos;
    @MockitoBean AccesoService acceso;
    @MockitoBean DisputaService disputas;
    @MockitoBean CampanaAvanceService avances;
    @MockitoBean ApiKeyService claves;
    @MockitoBean RecordatorioService recordatorios;
    @MockitoBean ContactoService contactos;
    @MockitoBean EmpresaService empresas;

    Debt foreign;

    @BeforeEach
    void fixtures() {
        Debtor owner = new Debtor(); owner.setId(1L); owner.setRut("18905214-6");
        Debtor visitor = new Debtor(); visitor.setId(2L); visitor.setRut("16482337-7");
        Organization creditor = new Organization(); creditor.setId(1L); creditor.setRut("76418902-7");
        Organization visitorOrg = new Organization(); visitorOrg.setId(2L); visitorOrg.setRut("77305118-6");
        foreign = new Debt(); foreign.setId(40L); foreign.setDebtor(owner); foreign.setCreditor(creditor);
        Batch batch = new Batch(); batch.setSender(creditor); foreign.setLastBatch(batch);
        foreign.setStatus(Debt.Status.disputed);
        when(debts.findById(40L)).thenReturn(Optional.of(foreign));
        when(debts.findById(41L)).thenReturn(Optional.empty());
        when(debtors.findByRut(visitor.getRut())).thenReturn(Optional.of(visitor));
        when(organizations.findByRut(visitorOrg.getRut())).thenReturn(Optional.of(visitorOrg));
    }

    private String token(String role) {
        String rut = role.equals("CREDITOR") ? "77305118-6" : "16482337-7";
        return "Bearer " + jwt.issue(rut, null, role, null, rut);
    }

    @Test
    void seg_dat_007_compara_json_completo_con_autorizacion_real_para_ambos_roles() throws Exception {
        for (String role : List.of("DEBTOR", "CREDITOR")) {
            for (String suffix : List.of("", "/simulate")) {
                String previous = null;
                for (long id : new long[]{40, 41}) {
                    String body = mvc.perform(get("/api/debts/" + id + suffix).header("Authorization", token(role)))
                            .andExpect(status().isForbidden())
                            .andExpect(content().json("{\"error\":\"No puedes ver esta deuda\"}", true))
                            .andReturn().getResponse().getContentAsString();
                    if (previous != null) assertEquals(previous, body);
                    previous = body;
                }
            }
        }
        verifyNoInteractions(installments, charges, events, eventos, descuentos);
    }

    @Test
    void sin_sesion_y_admin_son_rechazados_antes_de_buscar_el_recurso() throws Exception {
        mvc.perform(get("/api/debts/40")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/debts/41").header("Authorization", token("ADMIN")))
                .andExpect(status().isForbidden());
        verifyNoInteractions(debts);
    }

    @Test
    void checkout_interno_rechaza_ajena_e_inexistente_antes_de_cuotas_y_estados() throws Exception {
        for (long id : new long[]{40, 41}) {
            for (String cuota : List.of("12", "999", "98")) {
                mvc.perform(get("/internal/debts/" + id + "/checkout")
                                .header("X-Internal-Key", "clave-interna-de-prueba")
                                .param("debtorRut", "16482337-7").param("installmentIds", cuota))
                        .andExpect(status().isForbidden())
                        .andExpect(content().json("{\"error\":\"No puedes ver esta deuda\"}", true));
            }
        }
        verifyNoInteractions(installments, charges, events, eventos, descuentos);
    }

    @Test
    void la_agencia_conserva_su_cartera_aunque_no_sea_acreedora() {
        Organization agency = organizations.findByRut("77305118-6").orElseThrow();
        Batch batch = new Batch(); batch.setSender(agency); foreign.setLastBatch(batch);
        assertEquals(foreign, service.requireVisible(
                new com.tbridge.debt.security.JwtPrincipal("a", null, "CREDITOR", null, agency.getRut()), 40L));
    }

    @Test
    void certificado_ajeno_e_inexistente_son_indistinguibles_con_cualquier_accept() throws Exception {
        for (String role : List.of("DEBTOR", "CREDITOR")) {
            verificarPdfPrivado("/api/debts/%d/certificate", 40, 41, role);
        }
        verifyNoInteractions(installments, charges);
    }

    private void verificarPdfPrivado(String path, long ajeno, long inexistente, String role) throws Exception {
        for (MediaType accept : List.of(MediaType.APPLICATION_PDF, MediaType.APPLICATION_JSON, MediaType.ALL)) {
            String previous = null;
            for (long id : new long[]{ajeno, inexistente}) {
                String body = mvc.perform(get(path.formatted(id)).accept(accept).header("Authorization", token(role)))
                        .andExpect(status().isForbidden())
                        .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                        .andExpect(content().json("{\"error\":\"No puedes ver esta deuda\"}", true))
                        .andReturn().getResponse().getContentAsString();
                if (previous != null) assertEquals(previous, body);
                previous = body;
            }
            mvc.perform(get(path.formatted(ajeno)).accept(accept)).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void comprobante_ajeno_ausente_y_evento_no_pagado_tienen_el_mismo_json() throws Exception {
        DebtEvent payment = DebtEvent.de(foreign, DebtEvent.Type.payment_applied, DebtEvent.Actor.debtor);
        when(events.findById(50L)).thenReturn(Optional.of(payment));
        when(events.findById(52L)).thenReturn(Optional.of(
                DebtEvent.de(foreign, DebtEvent.Type.repacted, DebtEvent.Actor.debtor)));
        for (String role : List.of("DEBTOR", "CREDITOR")) {
            verificarPdfPrivado("/api/debts/pagos/%d/comprobante", 50, 51, role);
            for (long id : new long[]{50, 51, 52}) {
                mvc.perform(get("/api/debts/pagos/" + id + "/comprobante").header("Authorization", token(role)))
                        .andExpect(status().isForbidden())
                        .andExpect(content().json("{\"error\":\"No puedes ver esta deuda\"}", true));
            }
        }
        verifyNoInteractions(installments, descuentos, eventos);
        when(organizations.findByRut(foreign.getCreditor().getRut())).thenReturn(Optional.of(foreign.getCreditor()));
        assertEquals(payment, historial.pago(new com.tbridge.debt.security.JwtPrincipal(
                "owner", null, "CREDITOR", null, foreign.getCreditor().getRut()), 50L));
    }
}
