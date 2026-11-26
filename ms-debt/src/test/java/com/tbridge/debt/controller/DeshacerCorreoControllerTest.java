package com.tbridge.debt.controller;

import com.tbridge.debt.config.SecurityConfig;
import com.tbridge.debt.security.JwtTestConfig;
import com.tbridge.debt.service.ContactoOrigen;
import com.tbridge.debt.service.CorreoPersonalService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(DeshacerCorreoController.class)
@Import({SecurityConfig.class, JwtTestConfig.class})
@ActiveProfiles("test")
class DeshacerCorreoControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean CorreoPersonalService servicio;
    @MockitoBean ContactoOrigen origen;

    @Test void endpointPublicoNoExigeJwtYResponde204() throws Exception {
        when(origen.de(any())).thenReturn("192.0.2.1");
        mvc.perform(post("/api/debts/publico/correo/deshacer").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rut\":\"16482337-7\",\"codigo\":\"2345678A\"}"))
                .andExpect(status().isNoContent());
        verify(servicio).deshacer("16482337-7", "2345678A", "192.0.2.1");
    }

    @Test void validaElCuerpoAntesDeLlamarAlServicio() throws Exception {
        mvc.perform(post("/api/debts/publico/correo/deshacer").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rut\":\"\",\"codigo\":\"corto\"}"))
                .andExpect(status().isBadRequest());
        verifyNoInteractions(servicio);
    }
}
