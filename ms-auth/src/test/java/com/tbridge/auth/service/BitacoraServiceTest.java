package com.tbridge.auth.service;

import com.tbridge.auth.exception.ApiException;
import com.tbridge.auth.model.AdminLog;
import com.tbridge.auth.repository.AdminLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;

import java.time.Instant;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class BitacoraServiceTest {
    private AdminLogRepository repositorio;
    private BitacoraService servicio;

    @BeforeEach
    void preparar() {
        repositorio = mock(AdminLogRepository.class);
        servicio = new BitacoraService(repositorio);
    }

    @Test
    void normaliza_filtros_y_los_dias_incluyen_todo_el_dia_de_chile() {
        when(repositorio.buscar(any(), any(), any(), any(), any(), any())).thenReturn(Page.empty());

        servicio.buscar(" ADMIN@ ", "persona_creada", "76.086.428-5",
                LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-02"), 0, 500);

        verify(repositorio).buscar(eq("admin@"), eq(AdminLog.Action.persona_creada), eq("76086428-5"),
                eq(Instant.parse("2026-11-01T03:00:00Z")), eq(Instant.parse("2026-11-03T03:00:00Z")),
                argThat(p -> p.getPageSize() == 100));
    }

    @Test
    void una_accion_de_otro_servicio_es_una_pagina_vacia() {
        assertEquals(0, servicio.buscar(null, "uf_cargada", null, null, null, 0, 25).getTotalElements());
        verifyNoInteractions(repositorio);
    }

    @Test
    void rechaza_un_intervalo_invertido() {
        assertThrows(ApiException.class, () -> servicio.buscar(null, null, null,
                LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-01"), 0, 25));
    }
}
