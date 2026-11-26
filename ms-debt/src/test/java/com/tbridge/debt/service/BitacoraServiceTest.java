package com.tbridge.debt.service;

import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.model.AdminLog;
import com.tbridge.debt.repository.AdminLogRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
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
    void envia_los_filtros_normalizados_al_repositorio() {
        when(repositorio.buscar(any(), any(), any(), any(), any(), any())).thenReturn(Page.empty());
        servicio.buscar(" ADMIN@ ", "empresa_suspendida", "77.305.118-6", null, null, 2, 20);
        verify(repositorio).buscar(eq("admin@"), eq(AdminLog.Action.empresa_suspendida), eq("77305118-6"),
                isNull(), isNull(), argThat(p -> p.getPageNumber() == 2 && p.getPageSize() == 20));
    }

    @Test
    void accion_ajena_no_consulta_y_fechas_invertidas_fallan() {
        assertEquals(0, servicio.buscar(null, "admin_creado", null, null, null, 0, 25).getTotalElements());
        verifyNoInteractions(repositorio);
        assertThrows(ApiException.class, () -> servicio.buscar(null, null, null,
                LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-01"), 0, 25));
    }
}
