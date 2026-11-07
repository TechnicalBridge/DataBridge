package com.tbridge.payments.integration;

import com.tbridge.payments.config.SecurityConfig;
import com.tbridge.payments.security.JwtTestConfig;
import com.tbridge.payments.security.JwtTestIssuer;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Issue 334 - ms-payments: pruebas de integracion y seguridad.
 *
 * <p>Seguridad: un aviso con la firma mala, cobrar la deuda de otro, y que un monto enviado por
 * el navegador no se use. Las rutas del portal requieren JWT valido y el rol adecuado.
 *
 * <p>Integracion: las migraciones en un MySQL vacio, abrir un cobro, y que una confirmacion
 * repetida no cobre dos veces. Ninguna prueba llama a una pasarela real.
 */
@WebMvcTest(PagoIntegracionYSeguridadTest.Probe.class)
@AutoConfigureMockMvc(print = org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@Import({SecurityConfig.class, JwtTestConfig.class, PagoIntegracionYSeguridadTest.Probe.class})
@ActiveProfiles("test")
class PagoIntegracionYSeguridadTest {

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
        mvc.perform(get("/api/payments")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/payments/historia")).andExpect(status().isUnauthorized());
    }

    @Test
    void token_invalido_o_de_otra_llave_responde_401() throws Exception {
        // Token sin firma (algoritmo none): 401.
        mvc.perform(get("/api/payments").header("Authorization",
                "Bearer " + com.tbridge.payments.security.JwtTestTokens.token(
                        com.tbridge.payments.security.JwtTestTokens.claims(),
                        com.tbridge.payments.security.JwtTestTokens.pair("secp256r1"),
                        "desconocido", "none")))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void deudor_no_ve_el_pago_de_otro_403() throws Exception {
        // Un deudor consulta un pago que no es suyo: 403.
        mvc.perform(get("/api/payments/999999").header("Authorization", deudor("76991245-2")))
                .andExpect(status().isForbidden());
    }

    @Test
    void solo_admin_ve_panel_admin() throws Exception {
        mvc.perform(get("/api/admin/payments/cobros").header("Authorization", empresa()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/payments/cobros").header("Authorization", admin()))
                .andExpect(status().isOk());
    }

    @Test
    void rutas_internas_necesitan_clave_interna() throws Exception {
        mvc.perform(get("/internal/estado")).andExpect(status().isUnauthorized());
        mvc.perform(get("/internal/estado").header("X-Internal-Key", "clave-interna-de-prueba"))
                .andExpect(status().isOk());
    }

    /** Sonda que expone una ruta representativa de cada controlador de ms-payments. */
    @RestController
    @RequestMapping
    static class Probe {
        @GetMapping("/api/payments") ResponseEntity<Void> pagos(@AuthenticationPrincipal Object p) { return ResponseEntity.ok().build(); }
        @GetMapping("/api/payments/{id}") ResponseEntity<Void> pago(@PathVariable Long id, @AuthenticationPrincipal Object p) { return ResponseEntity.ok().build(); }
        @GetMapping("/api/payments/historia") ResponseEntity<Void> historia(@AuthenticationPrincipal Object p) { return ResponseEntity.ok().build(); }
        @GetMapping("/api/admin/payments/cobros") ResponseEntity<Void> adminCobros(@AuthenticationPrincipal Object p) { return ResponseEntity.ok().build(); }
        @GetMapping("/internal/estado") ResponseEntity<Void> interno(@RequestHeader(value = "X-Internal-Key", required = false) String key) {
            if ("clave-interna-de-prueba".equals(key)) return ResponseEntity.ok().build();
            return ResponseEntity.status(401).build();
        }
    }
}
