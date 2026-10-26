package com.tbridge.auth.controller;

import com.tbridge.auth.config.SecurityConfig;
import com.tbridge.auth.dto.response.CodigoEmitidoResponse;
import com.tbridge.auth.dto.response.PruebaDeCorreoResponse;
import com.tbridge.auth.exception.CorreoNoSalio;
import com.tbridge.auth.service.AuthService;
import com.tbridge.auth.service.MailService;
import com.tbridge.auth.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(InternalController.class)
@Import({SecurityConfig.class, com.tbridge.auth.security.JwtTestConfig.class})
@ActiveProfiles("test")
class InternalControllerTest {

    private static final String CUERPO = """
            {"rut":"16482337-7","canales":["correo"],"correo":"felipe.rojas@correo.cl","acreedor":"Patrimonio Inmuebles"}""";

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AuthService auth;

    @MockitoBean
    private MailService correo;

    @Test
    void el_correo_de_prueba_pide_la_clave_interna_y_una_direccion_valida() throws Exception {
        mvc.perform(post("/internal/correo/prueba").param("para", "equipo@gmail.com"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/correo/prueba").param("para", "equipo@gmail")
                        .header("X-Internal-Key", "clave-interna-de-prueba"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Esa direccion no es un correo valido"));

        verify(correo, never()).probar(any());
    }

    @Test
    void el_correo_de_prueba_responde_como_le_fue_y_nunca_la_clave() throws Exception {
        when(correo.probar("equipo@gmail.com")).thenReturn(new PruebaDeCorreoResponse(false, "equipo@gmail.com", null,
                "smtp.gmail.com:587", "DataBridge <equipo@gmail.com>", "sin modo de prueba: se le escribe a cualquier direccion",
                "535-5.7.8 Username and Password not accepted"));

        mvc.perform(post("/internal/correo/prueba").param("para", " Equipo@Gmail.com ")
                        .header("X-Internal-Key", "clave-interna-de-prueba"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enviado").value(false))
                .andExpect(jsonPath("$.servidor").value("smtp.gmail.com:587"))
                .andExpect(jsonPath("$.error").value("535-5.7.8 Username and Password not accepted"))
                .andExpect(jsonPath("$.clave").doesNotExist())
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    @Test
    void el_correo_de_prueba_acepta_un_alias_con_mas_escrito_tal_cual_en_la_url() throws Exception {
        when(correo.probar("equipo+deudor1@gmail.com")).thenReturn(new PruebaDeCorreoResponse(true,
                "equipo+deudor1@gmail.com", "equipo+deudor1@gmail.com", "smtp.gmail.com:587",
                "DataBridge <equipo@gmail.com>", "modo de prueba: directo solo a equipo@gmail.com; lo demas no sale", null));

        //  Sin codificar, el "+" de la URL llega como un espacio.
        mvc.perform(post("/internal/correo/prueba?para=equipo+deudor1@gmail.com")
                        .header("X-Internal-Key", "clave-interna-de-prueba"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.llegaA").value("equipo+deudor1@gmail.com"));
    }

    @Test
    void sin_la_clave_interna_nadie_emite_codigos() throws Exception {
        mvc.perform(post("/internal/codigos").contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/codigos").header("X-Internal-Key", "otra")
                        .contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                .andExpect(status().isUnauthorized());

        verify(auth, never()).emitirCodigo(any(), any(), any());
    }

    @Test
    void con_la_clave_emite_y_manda_el_correo() throws Exception {
        when(auth.emitirCodigo("16482337-7", List.of("correo"), null)).thenReturn(new CodigoEmitidoResponse(
                "K7M2QX", "16482337-7", List.of("correo"), Instant.parse("2026-09-25T12:00:00Z"), "Entra a ..."));

        mvc.perform(post("/internal/codigos").header("X-Internal-Key", "clave-interna-de-prueba")
                        .contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codigo").value("K7M2QX"))
                .andExpect(jsonPath("$.expiraEn").value("2026-09-25T12:00:00Z"));

        verify(correo).enviarCodigo("felipe.rojas@correo.cl", "K7M2QX", "Patrimonio Inmuebles");
    }

    @Test
    void si_el_correo_no_sale_responde_502_con_la_causa_y_anula_el_codigo() throws Exception {
        when(auth.emitirCodigo("16482337-7", List.of("correo"), null)).thenReturn(new CodigoEmitidoResponse(
                "K7M2QX", "16482337-7", List.of("correo"), Instant.parse("2026-09-25T12:00:00Z"), "Entra a ..."));
        doThrow(new CorreoNoSalio("550 5.1.1 The email account that you tried to reach does not exist", true))
                .when(correo).enviarCodigo(any(), any(), any());

        mvc.perform(post("/internal/codigos").header("X-Internal-Key", "clave-interna-de-prueba")
                        .contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.error").value("550 5.1.1 The email account that you tried to reach does not exist"))
                .andExpect(jsonPath("$.causa").value("direccion"))
                .andExpect(jsonPath("$.codigo").doesNotExist());

        //  Nadie recibio ese codigo: no puede quedar como el ultimo del deudor.
        verify(auth).anularCodigo("16482337-7", "K7M2QX");
    }

    @Test
    void si_falla_el_servidor_la_causa_es_el_servidor() throws Exception {
        when(auth.emitirCodigo("16482337-7", List.of("correo"), null)).thenReturn(new CodigoEmitidoResponse(
                "P4R8TW", "16482337-7", List.of("correo"), Instant.parse("2026-10-18T12:00:00Z"), "Entra a ..."));
        doThrow(new CorreoNoSalio("535-5.7.8 Username and Password not accepted", false))
                .when(correo).enviarRecordatorio(any(), any(), any(), any());

        mvc.perform(post("/internal/codigos").header("X-Internal-Key", "clave-interna-de-prueba")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO.replace("}", ",\"vence\":\"2026-10-20\"}")))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.causa").value("servidor"));

        verify(auth).anularCodigo("16482337-7", "P4R8TW");
    }

    @Test
    void si_el_correo_sale_el_codigo_no_se_anula() throws Exception {
        when(auth.emitirCodigo("16482337-7", List.of("correo"), null)).thenReturn(new CodigoEmitidoResponse(
                "K7M2QX", "16482337-7", List.of("correo"), Instant.parse("2026-09-25T12:00:00Z"), "Entra a ..."));

        mvc.perform(post("/internal/codigos").header("X-Internal-Key", "clave-interna-de-prueba")
                        .contentType(MediaType.APPLICATION_JSON).content(CUERPO))
                .andExpect(status().isOk());

        verify(auth, never()).anularCodigo(any(), any());
    }

    @Test
    void con_fecha_de_vencimiento_el_correo_es_el_recordatorio() throws Exception {
        when(auth.emitirCodigo("16482337-7", List.of("correo"), null)).thenReturn(new CodigoEmitidoResponse(
                "P4R8TW", "16482337-7", List.of("correo"), Instant.parse("2026-10-18T12:00:00Z"), "Entra a ..."));

        mvc.perform(post("/internal/codigos").header("X-Internal-Key", "clave-interna-de-prueba")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(CUERPO.replace("}", ",\"vence\":\"2026-10-20\"}")))
                .andExpect(status().isOk());

        verify(correo).enviarRecordatorio("felipe.rojas@correo.cl", "P4R8TW", "Patrimonio Inmuebles",
                LocalDate.of(2026, 10, 20));
        verify(correo, never()).enviarCodigo(any(), any(), any());
    }

    @Test
    void sin_rut_es_400() throws Exception {
        mvc.perform(post("/internal/codigos").header("X-Internal-Key", "clave-interna-de-prueba")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"canales\":[\"correo\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Falta el RUT"));
    }
}
