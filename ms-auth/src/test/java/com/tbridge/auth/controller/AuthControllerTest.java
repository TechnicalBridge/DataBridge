package com.tbridge.auth.controller;

import com.tbridge.auth.assembler.SesionModelAssembler;
import com.tbridge.auth.config.SecurityConfig;
import com.tbridge.auth.dto.response.EnlaceResponse;
import com.tbridge.auth.dto.response.UsuarioResponse;
import com.tbridge.auth.service.AuthService;
import com.tbridge.auth.service.SessionService;
import com.tbridge.auth.exception.ApiException;
import com.tbridge.auth.security.JwtService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Las puertas del portal vistas desde HTTP: codigos, forma del JSON, la cookie
 * de renovacion, los enlaces de HATEOAS y la validacion de lo que entra. La
 * logica de acceso esta simulada: se prueba en AuthServiceTest.
 */
@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtService.class, SesionModelAssembler.class, AuthControllerTest.Proxy.class})
@ActiveProfiles("test")
class AuthControllerTest {

    /** Lo que en produccion registra server.forward-headers-strategy=framework. */
    @TestConfiguration
    static class Proxy {
        @Bean
        ForwardedHeaderFilter forwardedHeaderFilter() {
            return new ForwardedHeaderFilter();
        }
    }

    private static final String RUT = "16482337-7";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtService jwt;

    @MockitoBean
    private AuthService auth;

    private AuthService.Sesion sesion() {
        return new AuthService.Sesion(jwt.issue(RUT, null, "DEBTOR", null, RUT), UsuarioResponse.deudor(RUT),
                new SessionService.Llave("llave-de-prueba", Instant.now().plus(7, ChronoUnit.DAYS)));
    }

    @Test
    void solicitar_codigo_es_202_y_no_entrega_el_secreto() throws Exception {
        when(auth.pedirCodigo(eq(RUT), eq(null), any())).thenReturn(new EnlaceResponse(true,
                "Si tus datos están registrados, recibirás un código de acceso por correo.", 15));
        mvc.perform(post("/api/auth/codigo").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rut\":\"" + RUT + "\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.expiraEnMinutos").value(15))
                .andExpect(jsonPath("$.codigo").doesNotExist())
                .andExpect(jsonPath("$.token").doesNotExist());
    }

    @Test
    void entrar_con_correo_y_codigo_usa_el_acceso_de_personal() throws Exception {
        when(auth.entrarConCodigoDePersonal(eq("persona@example.com"), eq("K7M2QX"), any())).thenReturn(sesion());
        mvc.perform(post("/api/auth/acceso").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"correo\":\"persona@example.com\",\"codigo\":\"K7M2QX\"}"))
                .andExpect(status().isOk());
        verify(auth).entrarConCodigoDePersonal(eq("persona@example.com"), eq("K7M2QX"), any());
    }

    @Test
    void entrar_no_acepta_rut_y_correo_a_la_vez() throws Exception {
        mvc.perform(post("/api/auth/acceso").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rut\":\"" + RUT + "\",\"correo\":\"persona@example.com\",\"codigo\":\"K7M2QX\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void entrar_devuelve_el_jwt_la_cookie_y_los_enlaces_con_la_direccion_publica() throws Exception {
        when(auth.entrarConCodigo(eq(RUT), eq("K7M2QX"), any())).thenReturn(sesion());

        mvc.perform(post("/api/auth/acceso")
                        .header("X-Forwarded-Host", "localhost:8080")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rut\":\"16482337-7\",\"codigo\":\"K7M2QX\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.rut").value(RUT))
                .andExpect(jsonPath("$.user.role").value("DEBTOR"))
                .andExpect(jsonPath("$.expiraEnSegundos").value(900))
                //  Un deudor no tiene nombre ni correo: no vienen, en vez de venir en null.
                .andExpect(jsonPath("$.user.nombre").doesNotExist())
                .andExpect(jsonPath("$._links.yo.href").value("http://localhost:8080/api/me"))
                .andExpect(jsonPath("$._links.renovar.href").value("http://localhost:8080/api/auth/refresh"))
                .andExpect(jsonPath("$._links.cerrar-sesion.href").value("http://localhost:8080/api/auth/logout"))
                .andExpect(header().string("Set-Cookie", allOf(
                        containsString("tb_renovacion=llave-de-prueba"),
                        containsString("HttpOnly"),
                        containsString("SameSite=Strict"),
                        containsString("Path=/api/auth"))));
    }

    @Test
    void la_ip_que_cuenta_es_la_del_cliente_y_no_la_del_gateway() throws Exception {
        when(auth.entrarConCodigo(any(), any(), any())).thenReturn(sesion());

        mvc.perform(post("/api/auth/acceso")
                        .header("X-Forwarded-For", "203.0.113.7, 172.21.0.10")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rut\":\"16482337-7\",\"codigo\":\"K7M2QX\"}"))
                .andExpect(status().isOk());

        verify(auth).entrarConCodigo(RUT, "K7M2QX", "203.0.113.7");
    }

    @Test
    void sin_codigo_es_400_con_un_mensaje_para_la_persona() throws Exception {
        mvc.perform(post("/api/auth/acceso")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rut\":\"16482337-7\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Falta el codigo"));
    }

    @Test
    void un_codigo_equivocado_responde_el_401_del_servicio() throws Exception {
        when(auth.entrarConCodigo(any(), any(), any()))
                .thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "El codigo no corresponde a ese RUT"));

        mvc.perform(post("/api/auth/acceso")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rut\":\"16482337-7\",\"codigo\":\"ZZZZZZ\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("El codigo no corresponde a ese RUT"));
    }

    @Test
    void pedir_enlace_con_un_correo_malo_es_400_y_uno_bueno_es_202() throws Exception {
        mvc.perform(post("/api/auth/enlace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"correo\":\"no-es-un-correo\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Correo no valido"));

        when(auth.pedirEnlace(eq(null), eq("camila.reyes@apofyx.cl"), any()))
                .thenReturn(new EnlaceResponse(true, "Si tus datos estan registrados, te llegara un enlace de acceso.", 15));
        mvc.perform(post("/api/auth/enlace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"correo\":\"camila.reyes@apofyx.cl\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.expiraEnMinutos").value(15));
    }

    @Test
    void renovar_sin_cookie_es_401() throws Exception {
        mvc.perform(post("/api/auth/refresh"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("No hay una sesion que renovar"));
    }

    @Test
    void si_otra_pestana_acaba_de_renovar_es_409_y_la_cookie_no_se_borra() throws Exception {
        when(auth.renovar(eq("llave-vieja"), any()))
                .thenThrow(new ApiException(HttpStatus.CONFLICT, "La sesion se acaba de renovar en otra pestana. Reintenta."));

        mvc.perform(post("/api/auth/refresh").cookie(new Cookie("tb_renovacion", "llave-vieja")))
                .andExpect(status().isConflict())
                .andExpect(header().doesNotExist("Set-Cookie"));
    }

    @Test
    void si_la_llave_se_revoco_es_401_y_la_cookie_se_borra() throws Exception {
        when(auth.renovar(eq("llave-robada"), any()))
                .thenThrow(new ApiException(HttpStatus.UNAUTHORIZED, "La sesion se cerro por seguridad. Vuelve a entrar."));

        mvc.perform(post("/api/auth/refresh").cookie(new Cookie("tb_renovacion", "llave-robada")))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));
    }

    @Test
    void cerrar_sesion_revoca_en_el_servidor_y_borra_la_cookie() throws Exception {
        mvc.perform(post("/api/auth/logout").cookie(new Cookie("tb_renovacion", "mi-llave")))
                .andExpect(status().isNoContent())
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));

        verify(auth).cerrar("mi-llave");
    }

    @Test
    void quien_soy_pide_el_jwt() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());

        mvc.perform(get("/api/me").header("Authorization", "Bearer " + jwt.issue(RUT, null, "DEBTOR", null, RUT)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.rut").value(RUT))
                .andExpect(jsonPath("$._links.self.href").value("http://localhost/api/me"));
    }

    @Test
    void el_deudor_pide_su_enlace_solo_con_el_rut() throws Exception {
        when(auth.pedirEnlace(eq("16482337-7"), eq(null), any()))
                .thenReturn(new EnlaceResponse(true, "Si tus datos estan registrados, te llegara un enlace de acceso.", 15));
        mvc.perform(post("/api/auth/enlace")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"rut\":\"16482337-7\"}"))
                .andExpect(status().isAccepted());
    }
}
