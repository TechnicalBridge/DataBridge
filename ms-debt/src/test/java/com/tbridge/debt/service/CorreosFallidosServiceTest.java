package com.tbridge.debt.service;

import com.tbridge.debt.dto.response.CorreosFallidosResponse;
import com.tbridge.debt.model.Debtor;
import com.tbridge.debt.repository.DebtRepository;
import com.tbridge.debt.repository.DebtorRepository;
import com.tbridge.debt.repository.EmpresaDelDeudor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Los correos fallidos del panel (#437): se ve el deudor, sus empresas, el
 * motivo, la fecha y cuantos rechazos seguidos lleva la direccion.
 */
class CorreosFallidosServiceTest {

    private static final Instant AHORA = Instant.parse("2026-11-13T12:00:00Z");

    private DebtorRepository deudores;
    private DebtRepository deudas;
    private CorreosFallidosService correos;

    @BeforeEach
    void preparar() {
        deudores = mock(DebtorRepository.class);
        deudas = mock(DebtRepository.class);
        correos = new CorreosFallidosService(deudores, deudas);
    }

    private static Debtor deudor(long id, String rut, String nombre, String email, int rechazos, Instant cuando) {
        Debtor deudor = new Debtor();
        deudor.setId(id);
        deudor.setRut(rut);
        deudor.setFullName(nombre);
        deudor.setEmail(email);
        for (int i = 0; i < rechazos; i++) {
            deudor.correoNoSalio("el servidor rechazo la direccion (550 5.1.1 User unknown)", true, cuando);
        }
        return deudor;
    }

    @Test
    void lista_los_deudores_con_sus_empresas_y_su_ultimo_rechazo() {
        Debtor felipe = deudor(1, "16482337-7", "Felipe Rojas", "felipe.rojas@correo.cl", 3, AHORA);
        Debtor ana = deudor(2, "18905214-6", "Ana Diaz", "ana.diaz@correo.cl", 1, AHORA.minusSeconds(3600));
        when(deudores.conCorreoFallido()).thenReturn(List.of(felipe, ana));
        when(deudas.empresasDeDeudores(List.of(1L, 2L))).thenReturn(List.of(
                new EmpresaDelDeudor(1L, "APOFYX"), new EmpresaDelDeudor(1L, "Otra SpA")));

        CorreosFallidosResponse respuesta = correos.conCorreoFallido();

        assertEquals(2, respuesta.total());
        CorreosFallidosResponse.Deudor primero = respuesta.deudores().getFirst();
        assertEquals("16482337-7", primero.rut());
        assertEquals("Felipe Rojas", primero.nombre());
        assertEquals("felipe.rojas@correo.cl", primero.email());
        assertEquals(List.of("APOFYX", "Otra SpA"), primero.empresas());
        assertEquals(AHORA, primero.cuando());
        assertEquals(3, primero.rechazos());
        assertTrue(primero.conProblemas());
        assertTrue(primero.motivo().contains("rechazo la direccion"));
        CorreosFallidosResponse.Deudor segundo = respuesta.deudores().get(1);
        assertEquals(List.of(), segundo.empresas(), "sin deudas no hay empresa que mostrar");
        assertEquals(1, segundo.rechazos());
        assertFalse(segundo.conProblemas(), "un rechazo todavia no deja de escribirle solo");
    }

    @Test
    void una_falla_del_servidor_de_correo_tambien_se_ve_pero_no_cuenta_como_rechazo() {
        Debtor deudor = deudor(1, "16482337-7", "Felipe Rojas", "felipe.rojas@correo.cl", 0, AHORA);
        deudor.correoNoSalio("el servidor de correo de DataBridge no lo acepto (535). Se reintenta solo",
                false, AHORA);
        when(deudores.conCorreoFallido()).thenReturn(List.of(deudor));
        when(deudas.empresasDeDeudores(List.of(1L))).thenReturn(List.of());

        CorreosFallidosResponse.Deudor fallido = correos.conCorreoFallido().deudores().getFirst();

        assertEquals(0, fallido.rechazos());
        assertFalse(fallido.conProblemas());
    }

    @Test
    void sin_correos_fallidos_no_consulta_las_empresas() {
        when(deudores.conCorreoFallido()).thenReturn(List.of());

        CorreosFallidosResponse respuesta = correos.conCorreoFallido();

        assertEquals(0, respuesta.total());
        assertTrue(respuesta.deudores().isEmpty());
        verify(deudas, never()).empresasDeDeudores(any());
    }
}
