package com.tbridge.payments.controller;

import com.tbridge.payments.config.SecurityConfig;
import com.tbridge.payments.dto.response.ReglasResponse;
import com.tbridge.payments.security.JwtTestIssuer;
import com.tbridge.payments.service.ReglasService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** El panel de administracion es solo del admin de DataBridge: ni una empresa ni un deudor entran. */
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc(print=org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint.NONE)
@WebMvcTest(AdminController.class)
@Import({SecurityConfig.class, com.tbridge.payments.security.JwtTestConfig.class})
@ActiveProfiles("test")
class AdminControllerTest {

    private static final String ESTADO = "/api/admin/payments/estado";

    @Autowired private MockMvc mvc;
    @Autowired private JwtTestIssuer jwt;
    @MockitoBean private ReglasService reglas;

    private String admin() {
        return "Bearer " + jwt.issue("9", "admin@databridge.cl", "ADMIN", "Administrador de DataBridge", null);
    }

    private String empresa() {
        return "Bearer " + jwt.issue("1", "camila.reyes@apofyx.cl", "CREDITOR", "Camila Reyes", "77305118-6");
    }

    private String deudor() {
        return "Bearer " + jwt.issue("16482337-7", null, "DEBTOR", null, "16482337-7");
    }

    @Test
    void el_admin_de_databridge_entra() throws Exception {
        mvc.perform(get(ESTADO).header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servicio").value("ms-payments"))
                .andExpect(jsonPath("$.ok").value(true));
    }

    @Test
    void las_reglas_las_ve_el_admin_y_una_empresa_no() throws Exception {
        when(reglas.reglas()).thenReturn(new ReglasResponse("ms-payments", List.of()));

        mvc.perform(get("/api/admin/payments/reglas").header("Authorization", admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.servicio").value("ms-payments"));
        mvc.perform(get("/api/admin/payments/reglas").header("Authorization", empresa()))
                .andExpect(status().isForbidden());
    }

    @Test
    void el_usuario_de_una_empresa_recibe_403() throws Exception {
        mvc.perform(get(ESTADO).header("Authorization", empresa()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("No tienes permiso para esto"));
    }

    @Test
    void un_deudor_recibe_403() throws Exception {
        mvc.perform(get(ESTADO).header("Authorization", deudor()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("No tienes permiso para esto"));
    }

    @Test
    void sin_sesion_es_401() throws Exception {
        mvc.perform(get(ESTADO))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("No autorizado"));
    }

    @Test
    void una_ruta_del_panel_que_no_existe_tampoco_se_le_abre_a_otro_rol() throws Exception {
        //  La regla es de toda la ruta /api/admin, no de cada metodo: un endpoint
        //  nuevo no puede quedar abierto por olvido.
        mvc.perform(get("/api/admin/payments/lo-que-venga").header("Authorization", empresa()))
                .andExpect(status().isForbidden());
    }
}
