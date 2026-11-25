package com.tbridge.auth.controller;

import com.tbridge.auth.config.SecurityConfig;
import com.tbridge.auth.service.ContactNoticeService;
import com.tbridge.auth.service.MailService;
import com.tbridge.auth.service.SessionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ContactoInternoController.class)
@Import({SecurityConfig.class, com.tbridge.auth.security.JwtTestConfig.class})
@ActiveProfiles("test")
@TestPropertySource(properties = "app.internal-key=clave-interna-de-prueba")
class ContactoInternoControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean MailService correo;
    @MockitoBean ContactNoticeService avisos;
    @MockitoBean SessionService sesiones;

    @Test void cerrarSesionesExigeClaveInterna() throws Exception {
        mvc.perform(post("/internal/contacto/sesiones").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rut\":\"16482337-7\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(sesiones);
    }

    @Test void cerrarSesionesConClaveRevocaAlDeudor() throws Exception {
        mvc.perform(post("/internal/contacto/sesiones").header("X-Internal-Key", "clave-interna-de-prueba")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"rut\":\"16482337-7\"}"))
                .andExpect(status().isOk());
        verify(sesiones).revocarDeDeudor("16482337-7");
    }
}
