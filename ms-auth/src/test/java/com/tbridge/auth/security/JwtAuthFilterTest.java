package com.tbridge.auth.security;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class JwtAuthFilterTest {

    private final JwtService jwt = com.tbridge.auth.security.JwtTestConfig.service();
    private final JwtAuthFilter filtro = new JwtAuthFilter(jwt);

    @Mock
    private FilterChain cadena;

    @AfterEach
    void limpiar() {
        SecurityContextHolder.clearContext();
    }

    private static MockHttpServletRequest peticion(String ruta, String autorizacion) {
        MockHttpServletRequest peticion = new MockHttpServletRequest("GET", ruta);
        peticion.setRequestURI(ruta);
        if (autorizacion != null) {
            peticion.addHeader("Authorization", autorizacion);
        }
        return peticion;
    }

    @Test
    void un_token_valido_deja_la_sesion_con_su_rol() throws Exception {
        String token = jwt.issue("16482337-7", null, "DEBTOR", null, "16482337-7");
        MockHttpServletRequest peticion = peticion("/api/debts", "Bearer " + token);
        MockHttpServletResponse respuesta = new MockHttpServletResponse();

        filtro.doFilter(peticion, respuesta, cadena);

        verify(cadena).doFilter(peticion, respuesta);
        JwtPrincipal principal = (JwtPrincipal) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        assertTrue(principal.isDebtor());
        assertEquals("16482337-7", principal.rut());
    }

    @Test
    void un_token_alterado_corta_con_401() throws Exception {
        String token = jwt.issue("16482337-7", null, "DEBTOR", null, "16482337-7");
        MockHttpServletResponse respuesta = new MockHttpServletResponse();

        filtro.doFilter(peticion("/api/debts", "Bearer " + token + "x"), respuesta, cadena);

        assertEquals(401, respuesta.getStatus());
        assertTrue(respuesta.getContentAsString().contains("\"error\""));
        verify(cadena, never()).doFilter(any(), any());
    }

    @Test
    void sin_token_sigue_de_largo_y_decide_la_seguridad_del_servicio() throws Exception {
        MockHttpServletRequest peticion = peticion("/api/debts", null);
        MockHttpServletResponse respuesta = new MockHttpServletResponse();

        filtro.doFilter(peticion, respuesta, cadena);

        verify(cadena).doFilter(peticion, respuesta);
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    void la_clave_de_api_del_contrato_no_se_lee_como_jwt() throws Exception {
        //  /api/v1 usa "Authorization: Bearer tbk_..." con una clave, no un JWT.
        MockHttpServletRequest peticion = peticion("/api/v1/carteras", "Bearer tbk_una-clave-de-api");
        MockHttpServletResponse respuesta = new MockHttpServletResponse();

        filtro.doFilter(peticion, respuesta, cadena);

        verify(cadena).doFilter(peticion, respuesta);
        assertEquals(200, respuesta.getStatus());
    }
}
