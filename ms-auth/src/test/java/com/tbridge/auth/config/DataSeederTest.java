package com.tbridge.auth.config;

import com.tbridge.auth.model.StaffUser;
import com.tbridge.auth.repository.StaffUserRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class DataSeederTest {

    private final StaffUserRepository personal = mock(StaffUserRepository.class);

    @Test
    void apagada_no_crea_ninguna_persona() {
        new DataSeeder(personal, false).run();
        verifyNoInteractions(personal);
    }

    @Test
    void la_empresa_de_demo_es_la_misma_que_en_ms_debt_y_usa_correo_reservado() {
        new DataSeeder(personal, true).run();
        ArgumentCaptor<StaffUser> persona = ArgumentCaptor.forClass(StaffUser.class);
        verify(personal).save(persona.capture());
        assertEquals("76000000-0", persona.getValue().getOrgRut());
        assertEquals("camila.reyes@example.com", persona.getValue().getEmail());
        assertEquals(StaffUser.Role.usuario, persona.getValue().getRole());
    }

    @Test
    void no_agrega_demo_si_ya_hay_personal_de_una_empresa() {
        when(personal.existsByRole(StaffUser.Role.usuario)).thenReturn(true);
        new DataSeeder(personal, true).run();
        verify(personal, never()).save(any());
    }

    @Test
    void no_reutiliza_un_correo_que_ya_es_de_un_admin() {
        when(personal.findByEmailIgnoreCase("camila.reyes@example.com")).thenReturn(Optional.of(new StaffUser()));
        new DataSeeder(personal, true).run();
        verify(personal, never()).save(any());
    }
}
