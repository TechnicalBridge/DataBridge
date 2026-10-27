package com.tbridge.debt.controller;

import com.tbridge.debt.security.JwtTestIssuer;
import com.tbridge.debt.config.SecurityConfig;
import com.tbridge.debt.dto.response.ClaveEmitidaResponse;
import com.tbridge.debt.dto.response.ClaveResponse;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.service.ApiKeyService;
import com.tbridge.debt.service.DebtService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Las claves de API desde el portal: solo la empresa, y solo las suyas. Con la entrega por API encendida. */
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@WebMvcTest(controllers = ClaveController.class, properties = "app.integracion.api=true")
@Import({SecurityConfig.class, com.tbridge.debt.security.JwtTestConfig.class})
@ActiveProfiles("test")
class ClaveControllerTest {

    @Autowired private MockMvc mvc;
    @Autowired private JwtTestIssuer jwt;
    @MockitoBean private ApiKeyService claves;
    @MockitoBean private DebtService debts;

    private final Organization apofyx = new Organization();

    @BeforeEach
    void preparar() {
        apofyx.setId(2L);
        apofyx.setRut("77305118-6");
        apofyx.setTradeName("APOFYX");
        when(debts.organizacionDe(any())).thenReturn(apofyx);
    }

    private String empresa() {
        return "Bearer " + jwt.issue("1", "camila.reyes@apofyx.cl", "CREDITOR", "Camila Reyes", "77305118-6");
    }

    private String deudor() {
        return "Bearer " + jwt.issue("16482337-7", null, "DEBTOR", null, "16482337-7");
    }

    @Test
    void un_deudor_no_ve_ni_emite_claves() throws Exception {
        mvc.perform(get("/api/claves").header("Authorization", deudor())).andExpect(status().isForbidden());
        mvc.perform(post("/api/claves").header("Authorization", deudor())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nombre\":\"x\"}"))
                .andExpect(status().isForbidden());
        verify(claves, never()).emitir(any(), any());
    }

    @Test
    void solo_la_clave_activa_ofrece_revocarse() throws Exception {
        when(claves.listar(apofyx)).thenReturn(List.of(
                new ClaveResponse(4L, "Servidor de APOFYX", "tbk_2x9Qa7Lm", Instant.parse("2026-09-19T12:00:00Z"),
                        Instant.parse("2026-09-24T09:00:00Z"), null, true),
                new ClaveResponse(3L, "Prueba", "tbk_old00000", Instant.parse("2026-09-01T12:00:00Z"),
                        null, Instant.parse("2026-09-10T12:00:00Z"), false)));

        mvc.perform(get("/api/claves").header("Authorization", empresa()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._embedded.claves[0]._links.revocar.href").exists())
                .andExpect(jsonPath("$._embedded.claves[1]._links.revocar").doesNotExist())
                //  La clave nunca vuelve: solo su prefijo.
                .andExpect(jsonPath("$._embedded.claves[0].clave").doesNotExist());
    }

    @Test
    void emitir_pide_un_nombre_y_devuelve_la_clave_una_vez() throws Exception {
        mvc.perform(post("/api/claves").header("Authorization", empresa())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nombre\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Ponle un nombre a la clave, para saber despues de que sistema es"));

        when(claves.emitir(apofyx, "Servidor de APOFYX")).thenReturn(new ClaveEmitidaResponse("tbk_secreta",
                "tbk_secreta", "APOFYX", "Guardala ahora: no se puede volver a mostrar"));
        mvc.perform(post("/api/claves").header("Authorization", empresa())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nombre\":\"Servidor de APOFYX\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clave").value("tbk_secreta"));
    }

    @Test
    void revocar_devuelve_la_clave_revocada() throws Exception {
        when(claves.revocar(apofyx, 4L)).thenReturn(new ClaveResponse(4L, "Servidor de APOFYX", "tbk_2x9Qa7Lm",
                Instant.parse("2026-09-19T12:00:00Z"), null, Instant.parse("2026-09-25T12:00:00Z"), false));

        mvc.perform(delete("/api/claves/4").header("Authorization", empresa()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activa").value(false));
    }
}
