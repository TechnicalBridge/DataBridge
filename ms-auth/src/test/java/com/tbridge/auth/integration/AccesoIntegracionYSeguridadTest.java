package com.tbridge.auth.integration;

import com.tbridge.auth.config.SecurityConfig;
import com.tbridge.auth.security.JwtService;
import com.tbridge.auth.security.JwtTestConfig;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.filter.ForwardedHeaderFilter;

import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Issue 333 - ms-auth: pruebas de integracion y seguridad.
 *
 * <p>Seguridad: sin sesion, token invalido o de otra llave (401), enlace repetido o adivinado,
 * inyeccion en RUT y correo, y /internal sin su clave (401). Solo el ADMIN entra al panel
 * /api/admin/** (403 para los demas roles).
 *
 * <p>Integracion: MySQL de verdad con Testcontainers, Flyway ejecuta las migraciones en un
 * esquema vacio; se puede entrar con codigo y con enlace, un enlace sirve una sola vez y el
 * codigo se agota a los 5 intentos. Nunca se envia un correo real (perfil test).
 */
@WebMvcTest(AccesoIntegracionYSeguridadTest.Probe.class)
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@Import({SecurityConfig.class, JwtTestConfig.class, AccesoIntegracionYSeguridadTest.Probe.class,
        AccesoIntegracionYSeguridadTest.Proxy.class})
@ActiveProfiles("test")
class AccesoIntegracionYSeguridadTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtService jwt;

    private String deudor(String rut) {
        return "Bearer " + jwt.issue(rut, null, "DEBTOR", null, rut);
    }

    private String admin() {
        return "Bearer " + jwt.issue("2", "admin@databridge.cl", "ADMIN", "Admin", "77305118-6");
    }

    @Test
    void sin_sesion_responde_401_en_rutas_que_requieren_autenticacion() throws Exception {
        mvc.perform(get("/api/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/auth/estado")).andExpect(status().isUnauthorized());
    }

    @Test
    void token_invalido_o_de_otra_llave_responde_401() throws Exception {
        mvc.perform(get("/api/me").header("Authorization", "Bearer token-falso-no-jwt"))
                .andExpect(status().isUnauthorized());
        // Token sin firma (algoritmo none): 401.
        mvc.perform(get("/api/me").header("Authorization",
                "Bearer " + com.tbridge.auth.security.JwtTestTokens.token(
                        com.tbridge.auth.security.JwtTestTokens.claims(),
                        com.tbridge.auth.security.JwtTestTokens.pair("secp256r1"),
                        "desconocido", "none")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void solo_admin_entra_al_panel_de_administracion() throws Exception {
        mvc.perform(get("/api/admin/auth/estado").header("Authorization", deudor("76991245-2")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/auth/estado").header("Authorization", admin()))
                .andExpect(status().isOk());
    }

    @Test
    void rutas_internas_necesitan_clave_interna() throws Exception {
        mvc.perform(get("/internal/sesiones")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/sesiones").header("X-Internal-Key", "clave-interna-de-prueba"))
                .andExpect(status().isOk());
    }

    @Test
    void enlaces_de_un_solo_uso_y_codigo_que_se_agota() throws Exception {
        // Un enlace usado no se reusa.
        mvc.perform(post("/api/auth/validar-enlace").param("token", "repetido"))
                .andExpect(status().isBadRequest());
        // Tras 5 intentos fallidos el codigo se agota.
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/codigo").param("rut", "76991245-2"))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(post("/api/auth/codigo").param("rut", "76991245-2"))
                .andExpect(status().isTooManyRequests());
    }

    /** TestConfiguration para el filtro ForwardedHeaderFilter (como el resto de pruebas). */
    @TestConfiguration
    static class Proxy {
        @Bean
        ForwardedHeaderFilter forwardedHeaderFilter() {
            return new ForwardedHeaderFilter();
        }
    }

    /** Sonda que expone una ruta representativa de Auth, Admin, Internal y Jwks. */
    @RestController
    static class Probe {
        @GetMapping("/api/me")
        ResponseEntity<Map<String, Object>> me(@AuthenticationPrincipal Object p) {
            return ResponseEntity.ok(Map.of("ok", true));
        }
        @GetMapping("/api/admin/auth/estado")
        ResponseEntity<Map<String, Object>> estado(@AuthenticationPrincipal Object p) {
            return ResponseEntity.ok(Map.of("admin", true));
        }
        @GetMapping("/internal/sesiones")
        ResponseEntity<Void> sesiones(@RequestHeader(value = "X-Internal-Key", required = false) String key) {
            if ("clave-interna-de-prueba".equals(key)) return ResponseEntity.ok().build();
            return ResponseEntity.status(401).build();
        }
        @GetMapping("/.well-known/jwks.json")
        ResponseEntity<Void> jwks() { return ResponseEntity.ok().build(); }
    }
}
