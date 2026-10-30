package com.tbridge.ai.service;

import com.tbridge.ai.client.DeudasClient;
import com.tbridge.ai.dto.request.Pregunta;
import com.tbridge.ai.dto.response.Animo;
import com.tbridge.ai.dto.response.Respuesta;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Una pregunta de punta a punta: las deudas, el animo y quien responde. */
class AsistenteServiceTest {

    private final DeudasClient deudas = mock(DeudasClient.class);
    private final LlmService llm = mock(LlmService.class);
    private final AsistenteService asistente = new AsistenteService(deudas, llm);

    @BeforeEach
    void preparar() {
        when(llm.proveedor()).thenReturn("xai");
        when(deudas.deudasDelDeudor("Bearer jwt")).thenReturn(MotorLocalTest.DEUDAS);
    }

    @Test
    void sin_llm_responde_el_motor_local() {
        when(llm.responder(anyList(), anyList(), anyString())).thenReturn(Optional.empty());

        Respuesta respuesta = asistente.responder(new Pregunta("cuánto debo?", null), "Bearer jwt");

        assertEquals(MotorLocal.responder("cuánto debo?", MotorLocalTest.DEUDAS), respuesta.reply());
        assertEquals("local-nlp", respuesta.source());
        assertEquals(3, respuesta.debts());
        assertEquals(new Animo("neutral", 0), respuesta.sentimiento());
    }

    @Test
    void con_llm_responde_el_llm() {
        when(llm.responder(anyList(), anyList(), anyString())).thenReturn(Optional.of("Debes dos deudas."));

        Respuesta respuesta = asistente.responder(new Pregunta("cuánto debo?", List.of()), "Bearer jwt");

        assertEquals("Debes dos deudas.", respuesta.reply());
        assertEquals("xai", respuesta.source());
    }

    @Test
    void el_animo_es_el_del_ultimo_mensaje_del_deudor_y_el_mensaje_nuevo_se_suma_a_la_conversacion() {
        when(llm.responder(anyList(), anyList(), anyString())).thenReturn(Optional.empty());
        List<Map<String, Object>> antes = List.of(
                Map.of("role", "user", "content", "gracias"),
                Map.of("role", "assistant", "content", "Con gusto."));

        Respuesta respuesta = asistente.responder(new Pregunta("estoy harto, esto es un abuso", antes), "Bearer jwt");

        assertEquals(new Animo("frustracion", 2), respuesta.sentimiento());
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Map<String, Object>>> historia = ArgumentCaptor.forClass(List.class);
        verify(llm).responder(historia.capture(), eq(MotorLocalTest.DEUDAS), eq("frustracion"));
        assertEquals(3, historia.getValue().size());
        assertEquals(Map.of("role", "user", "content", "estoy harto, esto es un abuso"), historia.getValue().get(2));
    }

    @Test
    void sin_mensaje_nuevo_cuenta_el_ultimo_del_deudor_en_la_conversacion() {
        when(llm.responder(anyList(), anyList(), anyString())).thenReturn(Optional.empty());
        List<Map<String, Object>> antes = List.of(
                Map.of("role", "user", "message", "¿esto es una estafa?"),
                Map.of("role", "assistant", "content", "..."));

        Respuesta respuesta = asistente.responder(new Pregunta("", antes), "Bearer jwt");

        assertEquals("desconfianza", respuesta.sentimiento().etiqueta());
        verify(llm).responder(any(), any(), eq("desconfianza"));
    }
    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.EnumSource(com.tbridge.ai.exception.DeudasException.Categoria.class)
    void debt_failure_never_generates(com.tbridge.ai.exception.DeudasException.Categoria category) {
        org.mockito.Mockito.when(deudas.deudasDelDeudor("Bearer jwt"))
                .thenThrow(new com.tbridge.ai.exception.DeudasException(category));
        try (var local = org.mockito.Mockito.mockStatic(MotorLocal.class)) {
            org.junit.jupiter.api.Assertions.assertThrows(com.tbridge.ai.exception.DeudasException.class,
                    () -> asistente.responder(new Pregunta("saldo", null), "Bearer jwt"));
            org.mockito.Mockito.verify(llm, org.mockito.Mockito.never()).responder(anyList(), anyList(), anyString());
            local.verifyNoInteractions();
        }
    }
}
