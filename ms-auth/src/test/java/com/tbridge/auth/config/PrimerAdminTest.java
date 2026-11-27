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
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class PrimerAdminTest {

    @Test
    void crea_y_rechaza_conflicto_sin_exponer_correo(org.springframework.boot.test.system.CapturedOutput salida) {
        arrancar("admin@example.org");
        ArgumentCaptor<StaffUser> creado = ArgumentCaptor.forClass(StaffUser.class);
        verify(personal).save(creado.capture());
        assertEquals("admin@example.org", creado.getValue().getEmail());
        when(personal.findByEmailIgnoreCase("empresa@example.net")).thenReturn(Optional.of(new StaffUser()));
        arrancar("empresa@example.net");
        assertTrue(salida.getAll().contains("configurado"));
        assertTrue(salida.getAll().contains("no se le da el rol admin"));
        org.junit.jupiter.api.Assertions.assertFalse(salida.getAll().contains("admin@example.org"));
        org.junit.jupiter.api.Assertions.assertFalse(salida.getAll().contains("empresa@example.net"));
        org.junit.jupiter.api.Assertions.assertFalse(java.util.regex.Pattern
                .compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}").matcher(salida.getAll()).find());
        verify(personal).save(any());
    }

    private final StaffUserRepository personal = mock(StaffUserRepository.class);
    private final ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(PrimerAdmin.class);
    private ch.qos.logback.classic.Level nivelAnterior;

    @org.junit.jupiter.api.AfterEach
    void restaurarNivel() {
        logger.setLevel(nivelAnterior);
    }

    @BeforeEach
    void sinNadie() {
        nivelAnterior = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.INFO);
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
