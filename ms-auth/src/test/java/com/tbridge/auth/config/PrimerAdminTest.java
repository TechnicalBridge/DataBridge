package com.tbridge.auth.config;

import com.tbridge.auth.model.StaffUser;
import com.tbridge.auth.repository.StaffUserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** El primer admin de DataBridge sale de ADMIN_CORREO, una sola vez, y nunca es de una empresa. */
class PrimerAdminTest {

    private final StaffUserRepository personal = mock(StaffUserRepository.class);

    @BeforeEach
    void sinNadie() {
        when(personal.existsByRole(StaffUser.Role.admin)).thenReturn(false);
        when(personal.findByEmailIgnoreCase(any())).thenReturn(Optional.empty());
    }

    private void arrancar(String correo) {
        new PrimerAdmin(personal, correo).run();
    }

    @Test
    void con_el_correo_configurado_crea_al_admin_sin_empresa() {
        arrancar("  Admin@DataBridge.cl ");

        ArgumentCaptor<StaffUser> creado = ArgumentCaptor.forClass(StaffUser.class);
        verify(personal).save(creado.capture());
        assertEquals("admin@databridge.cl", creado.getValue().getEmail());
        assertTrue(creado.getValue().esAdmin());
        assertNull(creado.getValue().getOrgRut(), "un admin no es de ninguna empresa");
        assertTrue(creado.getValue().habilitado());
    }

    @Test
    void sin_correo_no_hay_admin_y_nadie_entra_al_panel() {
        arrancar("");
        arrancar(null);

        verify(personal, never()).save(any());
    }

    @Test
    void si_ya_hay_un_admin_no_crea_otro() {
        when(personal.existsByRole(StaffUser.Role.admin)).thenReturn(true);

        arrancar("otro@databridge.cl");

        verify(personal, never()).save(any());
    }

    @Test
    void a_una_persona_de_una_empresa_no_se_le_da_el_rol_admin() {
        StaffUser camila = new StaffUser();
        camila.setEmail("camila.reyes@apofyx.cl");
        camila.setOrgRut("77305118-6");
        when(personal.findByEmailIgnoreCase("camila.reyes@apofyx.cl")).thenReturn(Optional.of(camila));

        arrancar("camila.reyes@apofyx.cl");

        verify(personal, never()).save(any());
        assertEquals(StaffUser.Role.usuario, camila.getRole());
    }

    @Test
    void algo_que_no_es_un_correo_no_crea_a_nadie() {
        arrancar("admin");

        verify(personal, never()).save(any());
    }
}
