package com.tbridge.debt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tbridge.debt.dto.response.PruebaSuscripcionResponse;
import com.tbridge.debt.dto.response.SecretoNuevoResponse;
import com.tbridge.debt.dto.response.SuscripcionAdminResponse;
import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.model.AdminLog;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.model.Subscription;
import com.tbridge.debt.repository.AdminLogRepository;
import com.tbridge.debt.repository.EntregasDeSuscripcion;
import com.tbridge.debt.repository.OutboxEventRepository;
import com.tbridge.debt.repository.SubscriptionRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Las suscripciones de avisos desde el panel (#438). */
class SuscripcionesAdminServiceTest {

    private static final String SECRETO = "whsec_secreto_de_prueba";

    private final SubscriptionRepository suscripciones = mock(SubscriptionRepository.class);
    private final OutboxEventRepository bandeja = mock(OutboxEventRepository.class);
    private final AdminLogRepository bitacora = mock(AdminLogRepository.class);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final SuscripcionesAdminService servicio = new SuscripcionesAdminService(suscripciones, bandeja, bitacora,
            json, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
    private HttpServer receptor;

    @AfterEach
    void apagarReceptor() {
        if (receptor != null) {
            receptor.stop(0);
        }
    }

    private static Subscription suscripcion(long id, String empresa, String rut, String url, boolean activa) {
        Organization organizacion = new Organization();
        organizacion.setTradeName(empresa);
        organizacion.setRut(rut);
        Subscription s = new Subscription();
        ReflectionTestUtils.setField(s, "id", id);
        s.setOrganization(organizacion);
        s.setUrl(url);
        s.setSecret(SECRETO);
        s.setActive(activa);
        return s;
    }

    private AdminLog anotado() {
        ArgumentCaptor<AdminLog> registro = ArgumentCaptor.forClass(AdminLog.class);
        verify(bitacora).save(registro.capture());
        return registro.getValue();
    }

    @Test
    void la_lista_va_por_empresa_con_sus_entregas_y_sin_credenciales_en_la_url() {
        Subscription patrimonio = suscripcion(2, "Patrimonio", "76418902-7", "https://patrimonio.cl/eventos", false);
        Subscription apofyx = suscripcion(1, "APOFYX", "77305118-6",
                "https://usuario:clave@apofyx.cl:8443/api/eventos?token=abc#x", true);
        apofyx.setEvents("[\"pago.confirmado\",\"deuda.saldada\"]");
        when(suscripciones.findAll()).thenReturn(List.of(patrimonio, apofyx));
        Instant ultima = Instant.parse("2026-11-18T12:00:00Z");
        when(bandeja.entregasPorSuscripcion()).thenReturn(List.of(new EntregasDeSuscripcion(1L, ultima, 3L, 1L)));

        List<SuscripcionAdminResponse> filas = servicio.listar();

        assertEquals(List.of("APOFYX", "Patrimonio"), filas.stream().map(SuscripcionAdminResponse::empresa).toList());
        assertEquals("https://apofyx.cl:8443/api/eventos", filas.get(0).url(), "sin usuario, clave ni parametros");
        assertEquals(List.of("pago.confirmado", "deuda.saldada"), filas.get(0).eventos());
        assertTrue(filas.get(0).activa());
        assertEquals(ultima, filas.get(0).ultimaEntrega());
        assertEquals(3, filas.get(0).pendientes());
        assertEquals(1, filas.get(0).fallidas());
        assertEquals(List.of(), filas.get(1).eventos(), "vacio: recibe todos");
        assertNull(filas.get(1).ultimaEntrega());
        assertEquals(0, filas.get(1).pendientes());
        assertFalse(json.valueToTree(filas).toString().contains(SECRETO), "el secreto nunca sale");
    }

    @Test
    void desactivar_y_activar_cambian_el_estado_y_quedan_en_la_historia_de_la_empresa() {
        Subscription s = suscripcion(1, "APOFYX", "77305118-6", "https://apofyx.cl/eventos?token=abc", true);
        when(suscripciones.findById(1L)).thenReturn(Optional.of(s));

        servicio.desactivar(1L, "admin@databridge.cl");

        assertFalse(s.isActive());
        verify(suscripciones).save(s);
        AdminLog registro = anotado();
        assertEquals(AdminLog.Action.suscripcion_desactivada, registro.getAction());
        assertEquals("77305118-6", registro.getOrgRut());
        assertEquals("la suscripción 1 a https://apofyx.cl/eventos", registro.getSubject());
        assertEquals("admin@databridge.cl", registro.getAdminEmail());

        ApiException otraVez = assertThrows(ApiException.class, () -> servicio.desactivar(1L, "admin@databridge.cl"));
        assertEquals(HttpStatus.CONFLICT, otraVez.getStatus());
        servicio.activar(1L, "admin@databridge.cl");
        assertTrue(s.isActive());
    }

    @Test
    void una_suscripcion_que_no_existe_es_404_y_no_se_anota_nada() {
        when(suscripciones.findById(9L)).thenReturn(Optional.empty());

        for (Runnable accion : List.<Runnable>of(() -> servicio.activar(9L, "a"), () -> servicio.desactivar(9L, "a"),
                () -> servicio.cambiarSecreto(9L, "a"), () -> servicio.probar(9L, "a"))) {
            assertEquals(HttpStatus.NOT_FOUND, assertThrows(ApiException.class, accion::run).getStatus());
        }
        verify(bitacora, never()).save(any());
    }

    @Test
    void el_secreto_nuevo_se_guarda_y_se_devuelve_una_vez() {
        Subscription s = suscripcion(1, "APOFYX", "77305118-6", "https://apofyx.cl/eventos", true);
        when(suscripciones.findById(1L)).thenReturn(Optional.of(s));

        SecretoNuevoResponse nuevo = servicio.cambiarSecreto(1L, "admin@databridge.cl");

        assertTrue(nuevo.secreto().startsWith("whsec_"));
        assertNotEquals(SECRETO, nuevo.secreto());
        assertEquals(nuevo.secreto(), s.getSecret(), "queda guardado (cifrado por SecretoCifrado)");
        verify(suscripciones).save(s);
        AdminLog registro = anotado();
        assertEquals(AdminLog.Action.suscripcion_secreto_cambiado, registro.getAction());
        assertFalse(registro.getSubject().contains(nuevo.secreto()), "el secreto no queda en la bitacora");
    }

    @Test
    void probar_manda_un_evento_de_prueba_firmado_que_el_receptor_puede_verificar() throws IOException {
        Map<String, String> llego = new ConcurrentHashMap<>();
        receptor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receptor.createContext("/eventos", intercambio -> {
            llego.put("evento", intercambio.getRequestHeaders().getFirst("X-Evento"));
            llego.put("id", intercambio.getRequestHeaders().getFirst("X-Evento-Id"));
            llego.put("marca", intercambio.getRequestHeaders().getFirst("X-Timestamp"));
            llego.put("firma", intercambio.getRequestHeaders().getFirst("X-Firma"));
            llego.put("cuerpo", new String(intercambio.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] respuesta = "{\"recibido\":true}".getBytes(StandardCharsets.UTF_8);
            intercambio.sendResponseHeaders(202, respuesta.length);
            intercambio.getResponseBody().write(respuesta);
            intercambio.close();
        });
        receptor.start();
        String url = "http://127.0.0.1:" + receptor.getAddress().getPort() + "/eventos";
        when(suscripciones.findById(1L)).thenReturn(Optional.of(suscripcion(1, "APOFYX", "77305118-6", url, false)));

        PruebaSuscripcionResponse resultado = servicio.probar(1L, "admin@databridge.cl");

        assertTrue(resultado.respondio());
        assertEquals(202, resultado.estado());
        assertEquals("{\"recibido\":true}", resultado.detalle());
        assertEquals("prueba", llego.get("evento"));
        assertTrue(llego.get("id").startsWith("evt_prueba_"));
        assertEquals(EventDispatcher.firma(SECRETO, Long.parseLong(llego.get("marca")), llego.get("cuerpo")),
                llego.get("firma"), "la firma calza con el secreto, como en los avisos reales");
        JsonNode cuerpo = json.readTree(llego.get("cuerpo"));
        assertEquals("prueba", cuerpo.path("tipo").asText());
        assertEquals("77305118-6", cuerpo.path("acreedorRut").asText(cuerpo.path("acreedor_rut").asText()));
        assertTrue(cuerpo.path("datos").has("mensaje") && cuerpo.path("datos").size() == 1, "sin datos de negocio");
        AdminLog registro = anotado();
        assertEquals(AdminLog.Action.suscripcion_probada, registro.getAction());
        assertTrue(registro.getSubject().endsWith(", que respondió 202"));
        verify(bandeja, never()).save(any());
    }

    @Test
    void probar_cuenta_lo_que_paso_si_el_receptor_rechaza_o_no_responde() throws IOException {
        receptor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        receptor.createContext("/eventos", intercambio -> {
            byte[] respuesta = "evento desconocido".getBytes(StandardCharsets.UTF_8);
            intercambio.sendResponseHeaders(400, respuesta.length);
            intercambio.getResponseBody().write(respuesta);
            intercambio.close();
        });
        receptor.start();
        int puerto = receptor.getAddress().getPort();
        when(suscripciones.findById(1L)).thenReturn(Optional.of(
                suscripcion(1, "APOFYX", "77305118-6", "http://127.0.0.1:" + puerto + "/eventos", true)));
        PruebaSuscripcionResponse rechazo = servicio.probar(1L, "admin@databridge.cl");
        assertFalse(rechazo.respondio());
        assertEquals(400, rechazo.estado());
        assertEquals("evento desconocido", rechazo.detalle());

        receptor.stop(0);
        receptor = null;
        when(suscripciones.findById(2L)).thenReturn(Optional.of(
                suscripcion(2, "APOFYX", "77305118-6", "http://127.0.0.1:" + puerto + "/eventos", true)));
        PruebaSuscripcionResponse caido = servicio.probar(2L, "admin@databridge.cl");
        assertFalse(caido.respondio());
        assertNull(caido.estado());
        assertEquals("No se pudo conectar con el receptor", caido.detalle());
        ArgumentCaptor<AdminLog> registros = ArgumentCaptor.forClass(AdminLog.class);
        verify(bitacora, org.mockito.Mockito.times(2)).save(registros.capture());
        assertTrue(registros.getAllValues().get(1).getSubject().endsWith(", que no respondió"));
    }

    @Test
    void por_que_no_hubo_respuesta_va_en_palabras() {
        assertEquals("El receptor no respondió a tiempo",
                SuscripcionesAdminService.motivoDe(new java.net.http.HttpTimeoutException("request timed out")));
        assertEquals("La dirección de la suscripción no es válida",
                SuscripcionesAdminService.motivoDe(new IllegalArgumentException("URI with undefined scheme")));
        assertEquals("Falló la conexión: Connection reset",
                SuscripcionesAdminService.motivoDe(new IOException("Connection reset")));
    }

    @Test
    void la_url_se_muestra_sin_credenciales() {
        assertEquals("https://apofyx.cl/eventos", SuscripcionesAdminService.sinCredenciales("https://apofyx.cl/eventos"));
        assertEquals("http://10.0.0.5:8000/x",
                SuscripcionesAdminService.sinCredenciales("http://u:p@10.0.0.5:8000/x?clave=1#frag"));
        assertEquals("(dirección no válida)", SuscripcionesAdminService.sinCredenciales("no es una url"));
    }
}
