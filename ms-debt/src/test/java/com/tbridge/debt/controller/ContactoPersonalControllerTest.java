package com.tbridge.debt.controller;
import com.tbridge.debt.config.SecurityConfig;
import com.tbridge.debt.security.JwtTestIssuer;
import com.tbridge.debt.security.JwtPrincipal;
import com.tbridge.debt.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@WebMvcTest(controllers={ContactoPersonalController.class,CorreoPersonalController.class})
@Import({SecurityConfig.class,com.tbridge.debt.security.JwtTestConfig.class})
@ActiveProfiles("test")
class ContactoPersonalControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean ContactoPersonalService phone;
    @MockitoBean CorreoPersonalService email;
    @MockitoBean ContactoOrigen origin;
    @Autowired JwtTestIssuer jwt;
    String token(String role) {return "Bearer "+jwt.issue("16482337-7",null,role,null,"16482337-7");}
    @Test void requiresSession() throws Exception {
        mvc.perform(patch("/api/debts/mis-datos/telefono").contentType("application/json").content("{\"telefono\":\"+56987654321\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(phone);
    }
    @Test void adminCannotReachContactOperations() throws Exception {
        mvc.perform(post("/api/debts/mis-datos/correo/verificacion").header("Authorization",token("ADMIN"))
                .contentType("application/json").content("{\"correo\":\"nuevo@correo.cl\"}"))
                .andExpect(status().isForbidden());
        verifyNoInteractions(email);
    }
    @Test void passesJwtIdentityAndNeverReturnsCode() throws Exception {
        when(email.solicitar(any(),anyString(),any())).thenReturn(new CorreoPersonalService.Pendiente("opaque",java.time.Instant.now().plusSeconds(900)));
        mvc.perform(post("/api/debts/mis-datos/correo/verificacion").header("Authorization",token("DEBTOR"))
                .contentType("application/json").content("{\"correo\":\"nuevo@correo.cl\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.solicitudId").value("opaque"))
                .andExpect(jsonPath("$.codigo").doesNotExist()).andExpect(jsonPath("$.correo").doesNotExist());
        verify(email).solicitar(argThat(p->p.isDebtor() && p.rut().equals("16482337-7")),eq("nuevo@correo.cl"),any());
    }
}
