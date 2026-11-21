package com.tbridge.debt.service;

import com.tbridge.debt.exception.CarteraInvalida;
import com.tbridge.debt.model.ApiKey;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.ApiKeyRepository;
import com.tbridge.debt.repository.OrganizationRepository;
import com.tbridge.debt.util.Hash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.isNull;

class ApiKeyServiceTest {

    private ApiKeyRepository claves;
    private OrganizationRepository organizations;
    private ApiKeyService servicio;
    private static final Instant AHORA = Instant.parse("2026-11-21T12:00:00Z");

    @BeforeEach
    void setUp() {
        claves = mock(ApiKeyRepository.class);
        organizations = mock(OrganizationRepository.class);
        servicio = new ApiKeyService(claves, organizations, Clock.fixed(AHORA, ZoneOffset.UTC));
    }

    @Test
    void autenticar_con_empresa_activa_funciona() {
        Organization org = new Organization();
        org.setStatus(Organization.Status.active);

        ApiKey apiKey = new ApiKey();
        apiKey.setOrganization(org);

        when(claves.findByKeyHashAndRevokedAtIsNull(Hash.sha256("tbk_prueba")))
                .thenReturn(Optional.of(apiKey));

        Organization autenticada = servicio.autenticar("Bearer tbk_prueba");
        assertNotNull(autenticada);
        assertEquals(org, autenticada);
        verify(claves).registrarUsoSiVencido(null, AHORA, AHORA.minusSeconds(300));
        verify(claves, never()).save(any());
        assertEquals(null, apiKey.getLastUsedAt());
    }

    @Test
    void uso_reciente_no_escribe_y_limite_exacto_actualiza() {
        Organization org = new Organization();
        org.setStatus(Organization.Status.active);
        ApiKey registro = new ApiKey();
        registro.setOrganization(org);
        registro.setLastUsedAt(AHORA.minusSeconds(299));
        when(claves.findByKeyHashAndRevokedAtIsNull(Hash.sha256("tbk_prueba")))
                .thenReturn(Optional.of(registro));
        assertEquals(org, servicio.autenticar("Bearer tbk_prueba"));
        verify(claves, never()).registrarUsoSiVencido(isNull(), any(), any());
        registro.setLastUsedAt(AHORA.minusSeconds(300));
        assertEquals(org, servicio.autenticar("Bearer tbk_prueba"));
        verify(claves).registrarUsoSiVencido(null, AHORA, AHORA.minusSeconds(300));
        assertEquals(AHORA.minusSeconds(300), registro.getLastUsedAt());
    }

    @Test
    void claves_ausentes_invalidas_o_revocadas_no_escriben() {
        for (String header : new String[]{null, "", "Basic abc", "Bearer ", "Bearer invalida", "Bearer revocada"}) {
            CarteraInvalida err = assertThrows(CarteraInvalida.class, () -> servicio.autenticar(header));
            assertEquals(401, err.getStatus());
        }
        verify(claves, never()).registrarUsoSiVencido(any(), any(), any());
        verify(claves, never()).save(any());
    }

    @Test
    void autenticar_con_empresa_suspendida_lanza_403() {
        Organization org = new Organization();
        org.setStatus(Organization.Status.suspended);

        ApiKey apiKey = new ApiKey();
        apiKey.setOrganization(org);

        when(claves.findByKeyHashAndRevokedAtIsNull(Hash.sha256("tbk_prueba")))
                .thenReturn(Optional.of(apiKey));

        CarteraInvalida err = assertThrows(CarteraInvalida.class, () -> servicio.autenticar("Bearer tbk_prueba"));
        assertEquals("empresa_suspendida", err.getCodigo());
        assertEquals(403, err.getStatus());
    }
}
