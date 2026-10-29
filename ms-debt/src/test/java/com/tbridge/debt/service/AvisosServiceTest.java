package com.tbridge.debt.service;

import com.tbridge.debt.dto.response.AvisosResponse;
import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.model.AdminLog;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.model.OutboxEvent;
import com.tbridge.debt.model.Subscription;
import com.tbridge.debt.repository.AdminLogRepository;
import com.tbridge.debt.repository.OutboxEventRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Los avisos a las empresas que no se entregan, en el panel (#139): se ven por
 * destinatario sin su contenido, y reintentarlos los deja en cola y queda anotado.
 */
class AvisosServiceTest {

    private OutboxEventRepository bandeja;
    private AdminLogRepository bitacora;
    private AvisosService avisos;

    @BeforeEach
    void preparar() {
        bandeja = mock(OutboxEventRepository.class);
        bitacora = mock(AdminLogRepository.class);
        avisos = new AvisosService(bandeja, bitacora);
    }

    private static Subscription suscripcion(long id, String empresa, String rut, String url) {
        Organization organizacion = new Organization();
        organizacion.setRut(rut);
        organizacion.setTradeName(empresa);
        Subscription suscripcion = mock(Subscription.class);
        when(suscripcion.getId()).thenReturn(id);
        when(suscripcion.getOrganization()).thenReturn(organizacion);
        when(suscripcion.getUrl()).thenReturn(url);
        return suscripcion;
    }

    /** Un aviso con tantos intentos fallidos: con seis, queda como fallido. */
    private static OutboxEvent aviso(Subscription destino, int fallos) {
        OutboxEvent aviso = OutboxEvent.para(destino, "evt", "pago.confirmado",
                "{\"rut_deudor\":\"18905214-6\",\"monto\":410000}", Instant.parse("2026-10-20T15:00:00Z"));
        for (int i = 0; i < fallos; i++) {
            aviso.fallo("Connection refused");
        }
        return aviso;
    }

    @Test
    void los_avisos_sin_entregar_salen_por_destinatario_el_de_mas_fallidos_primero() {
        Subscription apofyx = suscripcion(1, "APOFYX", "77305118-6", "http://host.docker.internal:8000/api/eventos?token=secreto");
        Subscription otra = suscripcion(2, "Otra SpA", "76000000-0", "https://otra.cl/avisos");
        when(bandeja.findTop500ByStatusInOrderByIdDesc(any())).thenReturn(List.of(
                aviso(otra, 1), aviso(apofyx, 6), aviso(apofyx, 6)));
        when(bandeja.countByStatus(OutboxEvent.Status.pending)).thenReturn(1L);
        when(bandeja.countByStatus(OutboxEvent.Status.failed)).thenReturn(2L);

        AvisosResponse respuesta = avisos.sinEntregar();

        assertEquals(1, respuesta.pendientes());
        assertEquals(2, respuesta.fallidos());
        AvisosResponse.Destino primero = respuesta.destinos().getFirst();
        assertEquals("APOFYX", primero.empresa());
        assertEquals(2, primero.fallidos());
        assertEquals(0, primero.pendientes());
        assertEquals("http://host.docker.internal:8000", primero.direccion(), "sin la ruta ni los parametros");
        AvisosResponse.Aviso fallido = primero.avisos().getFirst();
        assertEquals("failed", fallido.estado());
        assertEquals(6, fallido.intentos());
        assertEquals("Connection refused", fallido.ultimoError());
        assertNull(fallido.proximoIntento(), "un fallido espera un reintento a mano");
        AvisosResponse.Destino segundo = respuesta.destinos().get(1);
        assertEquals("Otra SpA", segundo.empresa());
        assertEquals(1, segundo.pendientes());
        assertTrue(segundo.avisos().getFirst().proximoIntento() != null);
    }

    @Test
    void ningun_aviso_muestra_su_contenido() {
        assertEquals(7, AvisosResponse.Aviso.class.getRecordComponents().length);
        for (var campo : AvisosResponse.Aviso.class.getRecordComponents()) {
            assertTrue(!campo.getName().toLowerCase().contains("payload") && !campo.getName().contains("contenido"));
        }
    }

    @Test
    void reintentar_lo_deja_en_cola_para_ahora_y_lo_anota_sobre_la_empresa() {
        OutboxEvent fallido = aviso(suscripcion(1, "APOFYX", "77305118-6", "https://apofyx.cl/eventos"), 6);
        when(bandeja.findById(9L)).thenReturn(Optional.of(fallido));
        Instant antes = Instant.now();

        AvisosResponse.Aviso enCola = avisos.reintentar(9L, "admin@databridge.cl");

        assertEquals(OutboxEvent.Status.pending, fallido.getStatus());
        assertTrue(!fallido.getNextAttemptAt().isBefore(antes), "queda para ahora, no para dentro de un rato");
        assertEquals(6, fallido.getAttempts().intValue(), "conserva sus intentos");
        assertEquals("pending", enCola.estado());
        verify(bandeja).save(fallido);
        ArgumentCaptor<AdminLog> registro = ArgumentCaptor.forClass(AdminLog.class);
        verify(bitacora).save(registro.capture());
        assertEquals("admin@databridge.cl", registro.getValue().getAdminEmail());
        assertEquals(AdminLog.Action.aviso_reintentado, registro.getValue().getAction());
        assertEquals("77305118-6", registro.getValue().getOrgRut());
        assertEquals("1 aviso a https://apofyx.cl", registro.getValue().getSubject());
    }

    @Test
    void si_vuelve_a_fallar_queda_otra_vez_como_fallido() {
        OutboxEvent fallido = aviso(suscripcion(1, "APOFYX", "77305118-6", "https://apofyx.cl"), 6);
        fallido.reintentar();
        fallido.fallo("Connection refused");

        assertEquals(OutboxEvent.Status.failed, fallido.getStatus());
        assertEquals(7, fallido.getAttempts().intValue());
    }

    @Test
    void un_aviso_entregado_o_que_no_existe_no_se_reintenta() {
        OutboxEvent entregado = aviso(suscripcion(1, "APOFYX", "77305118-6", "https://apofyx.cl"), 0);
        entregado.entregado();
        when(bandeja.findById(1L)).thenReturn(Optional.of(entregado));
        when(bandeja.findById(2L)).thenReturn(Optional.empty());

        assertEquals(HttpStatus.CONFLICT,
                assertThrows(ApiException.class, () -> avisos.reintentar(1L, "admin@databridge.cl")).getStatus());
        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ApiException.class, () -> avisos.reintentar(2L, "admin@databridge.cl")).getStatus());
        verify(bandeja, never()).save(any());
        verify(bitacora, never()).save(any());
    }

    @Test
    void reintentar_un_destinatario_pone_en_cola_todos_sus_fallidos_y_lo_anota_una_vez() {
        Subscription apofyx = suscripcion(1, "APOFYX", "77305118-6", "https://apofyx.cl/eventos");
        List<OutboxEvent> fallidos = List.of(aviso(apofyx, 6), aviso(apofyx, 6), aviso(apofyx, 6));
        when(bandeja.findBySubscriptionIdAndStatus(1L, OutboxEvent.Status.failed)).thenReturn(fallidos);

        assertEquals(3, avisos.reintentarDestino(1L, "admin@databridge.cl"));

        assertTrue(fallidos.stream().allMatch(a -> a.getStatus() == OutboxEvent.Status.pending));
        ArgumentCaptor<AdminLog> registro = ArgumentCaptor.forClass(AdminLog.class);
        verify(bitacora).save(registro.capture());
        assertEquals("3 avisos a https://apofyx.cl", registro.getValue().getSubject());
    }

    @Test
    void un_destinatario_sin_fallidos_responde_404() {
        when(bandeja.findBySubscriptionIdAndStatus(5L, OutboxEvent.Status.failed)).thenReturn(List.of());

        assertEquals(HttpStatus.NOT_FOUND,
                assertThrows(ApiException.class, () -> avisos.reintentarDestino(5L, "admin@databridge.cl")).getStatus());
        verify(bitacora, never()).save(any());
    }

    @Test
    void la_direccion_sin_ruta_ni_parametros() {
        assertEquals("https://apofyx.cl", AvisosService.direccion("https://apofyx.cl/api/eventos?token=x"));
        assertEquals("http://localhost:8000", AvisosService.direccion("http://localhost:8000/x"));
        assertEquals("(direccion no valida)", AvisosService.direccion("no es una url"));
    }
}
