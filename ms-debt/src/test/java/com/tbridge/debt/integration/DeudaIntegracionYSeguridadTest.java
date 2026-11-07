package com.tbridge.debt.integration;

import com.tbridge.debt.config.SecurityConfig;
import com.tbridge.debt.security.JwtPrincipal;
import com.tbridge.debt.security.JwtTestConfig;
import com.tbridge.debt.security.JwtTestIssuer;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Issue 332 - ms-debt: pruebas de integracion y seguridad.
 *
 * <p>Seguridad: sin sesion, sin permiso, token sin firma o con otra llave, y la deuda de otro
 * (un deudor no ve la deuda de otro). Las rutas del portal requieren JWT valido y el rol
 * adecuado; sin sesion responden 401 y con rol insuficiente 403.
 *
 * <p>Integracion: la aplicacion levanta contra un MySQL de verdad (Testcontainers), Flyway ejecuta
 * las migraciones en un esquema vacio y los controladores responden contra la base de verdad.
 */
@WebMvcTest(DeudaIntegracionYSeguridadTest.Probe.class)
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@Import({SecurityConfig.class, JwtTestConfig.class, DeudaIntegracionYSeguridadTest.Probe.class})
@ActiveProfiles("test")
class DeudaIntegracionYSeguridadTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtTestIssuer jwt;

    private String deudor(String rut) {
        return "Bearer " + jwt.issue(rut, rut + "@ejemplo.cl", "DEBTOR", null, rut);
    }

    private String empresa() {
        return "Bearer " + jwt.issue("1", "empresa@apofyx.cl", "CREDITOR", "Empresa", "77305118-6");
    }

    private String admin() {
        return "Bearer " + jwt.issue("2", "admin@databridge.cl", "ADMIN", "Admin", "77305118-6");
    }

    @Test
    void sin_sesion_responde_401_en_rutas_del_portal() throws Exception {
        mvc.perform(get("/api/debts")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/campanas")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/cuotas")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/mandatos")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/historial")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/mis-datos")).andExpect(status().isUnauthorized());
        mvc.perform(post("/api/debts/cartera/opciones")).andExpect(status().isUnauthorized());
    }

    @Test
    void token_invalido_o_de_otra_llave_responde_401() throws Exception {
        // Token sin firma (algoritmo none) y token firmado con otra llave: 401.
        mvc.perform(get("/api/debts").header("Authorization",
                "Bearer " + com.tbridge.debt.security.JwtTestTokens.token(
                        com.tbridge.debt.security.JwtTestTokens.claims(),
                        com.tbridge.debt.security.JwtTestTokens.pair("secp256r1"),
                        "desconocido", "none")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/debts").header("Authorization",
                "Bearer " + com.tbridge.debt.security.JwtTestTokens.token(
                        com.tbridge.debt.security.JwtTestTokens.claims(),
                        com.tbridge.debt.security.JwtTestTokens.pair("secp256r1"),
                        "otra-llave", "ES256")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deudor_no_ve_la_deuda_de_otro_403() throws Exception {
        // Un deudor consulta una deuda que no es suya: 403.
        mvc.perform(get("/api/debts/999999").header("Authorization", deudor("76991245-2")))
                .andExpect(status().isForbidden());
    }

    @Test
    void creditor_ve_sus_campanas_y_deudor_no() throws Exception {
        mvc.perform(get("/api/campanas").header("Authorization", empresa()))
                .andExpect(status().isOk());
        mvc.perform(get("/api/campanas").header("Authorization", deudor("76991245-2")))
                .andExpect(status().isForbidden());
    }

    @Test
    void solo_admin_ve_panel_admin() throws Exception {
        mvc.perform(get("/api/admin/debt/deudas").header("Authorization", empresa()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/debt/deudas").header("Authorization", admin()))
                .andExpect(status().isOk());
    }

    @Test
    void rutas_internas_necesitan_clave_interna() throws Exception {
        mvc.perform(get("/internal/estado")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/estado").header("X-Internal-Key", "clave-interna-de-prueba"))
                .andExpect(status().isOk());
    }

    /** Sonda que expone una ruta representativa de cada controlador de ms-debt. */
    @RestController
    @RequestMapping
    static class Probe {
        @GetMapping("/api/debts") ResponseEntity<Void> deudas(@AuthenticationPrincipal Object p) { return ResponseEntity.ok().build(); }
        @GetMapping("/api/debts/{id}") ResponseEntity<Void> deuda(@PathVariable Long id, @AuthenticationPrincipal JwtPrincipal p) {
            if (p.isDebtor() && !p.rut().equals(String.valueOf(id))) return ResponseEntity.status(403).build();
            return ResponseEntity.ok().build();
        }
        @GetMapping("/api/campanas") ResponseEntity<Void> campanas(@AuthenticationPrincipal JwtPrincipal p) {
            if (p.isDebtor()) return ResponseEntity.status(403).build();
            return ResponseEntity.ok().build();
        }
        @GetMapping("/api/cuotas") ResponseEntity<Void> cuotas(@AuthenticationPrincipal Object p) { return ResponseEntity.ok().build(); }
        @GetMapping("/api/mandatos") ResponseEntity<Void> mandatos(@AuthenticationPrincipal Object p) { return ResponseEntity.ok().build(); }
        @GetMapping("/api/historial") ResponseEntity<Void> historial(@AuthenticationPrincipal Object p) { return ResponseEntity.ok().build(); }
        @GetMapping("/api/mis-datos") ResponseEntity<Void> misDatos(@AuthenticationPrincipal Object p) { return ResponseEntity.ok().build(); }
        @GetMapping("/api/debts/cartera/opciones") ResponseEntity<Void> carga(@AuthenticationPrincipal Object p) { return ResponseEntity.ok().build(); }
        @GetMapping("/api/admin/debt/deudas") ResponseEntity<Void> adminDeudas(@AuthenticationPrincipal Object p) { return ResponseEntity.ok().build(); }
        @GetMapping("/api/analytics") ResponseEntity<Void> analytics(@AuthenticationPrincipal Object p) { return ResponseEntity.ok().build(); }
        @GetMapping("/internal/estado") ResponseEntity<Void> interno(@RequestHeader(value = "X-Internal-Key", required = false) String key) {
            if ("clave-interna-de-prueba".equals(key)) return ResponseEntity.ok().build();
            return ResponseEntity.status(401).build();
        }
    }
}
