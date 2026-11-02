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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiKeyServiceTest {

    private ApiKeyRepository claves;
    private OrganizationRepository organizations;
    private ApiKeyService servicio;

    @BeforeEach
    void setUp() {
        claves = mock(ApiKeyRepository.class);
        organizations = mock(OrganizationRepository.class);
        servicio = new ApiKeyService(claves, organizations);
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
        verify(claves).save(apiKey);
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
