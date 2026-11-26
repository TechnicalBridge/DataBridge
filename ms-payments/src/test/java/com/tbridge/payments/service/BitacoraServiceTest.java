package com.tbridge.payments.service;

import com.tbridge.payments.exception.ApiException;
import com.tbridge.payments.model.AdminLog;
import com.tbridge.payments.repository.AdminLogRepository;
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
    void filtra_por_persona_y_accion() {
        when(repositorio.buscar(any(), any(), any(), any(), any())).thenReturn(Page.empty());
        servicio.buscar(" ADMIN@ ", "uf_cargada", null, null, null, 1, 25);
        verify(repositorio).buscar(eq("admin@"), eq(AdminLog.Action.uf_cargada), isNull(), isNull(),
                argThat(p -> p.getPageNumber() == 1));
    }

    @Test
    void empresa_o_accion_ajena_no_coinciden_y_fechas_invertidas_fallan() {
        assertEquals(0, servicio.buscar(null, null, "77305118-6", null, null, 0, 25).getTotalElements());
        assertEquals(0, servicio.buscar(null, "empresa_creada", null, null, null, 0, 25).getTotalElements());
        verifyNoInteractions(repositorio);
        assertThrows(ApiException.class, () -> servicio.buscar(null, null, null,
                LocalDate.parse("2026-11-02"), LocalDate.parse("2026-11-01"), 0, 25));
    }
}
