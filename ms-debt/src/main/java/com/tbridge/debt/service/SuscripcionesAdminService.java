package com.tbridge.debt.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.debt.dto.evento.EventoV1;
import com.tbridge.debt.dto.response.PruebaSuscripcionResponse;
import com.tbridge.debt.dto.response.SecretoNuevoResponse;
import com.tbridge.debt.dto.response.SuscripcionAdminResponse;
import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.model.AdminLog;
import com.tbridge.debt.model.Subscription;
import com.tbridge.debt.repository.AdminLogRepository;
import com.tbridge.debt.repository.EntregasDeSuscripcion;
import com.tbridge.debt.repository.OutboxEventRepository;
import com.tbridge.debt.repository.SubscriptionRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Las suscripciones de avisos de las empresas, desde el panel (#438): verlas,
 * activarlas o desactivarlas, cambiarles el secreto y probarlas, sin SQL. Cada
 * accion queda en la bitacora del panel, en la historia de la empresa.
 *
 * <p>Desactivar es una pausa: no se encolan avisos nuevos para esa suscripcion
 * ({@link EventosService} solo usa las activas) y lo pendiente espera, sin
 * gastar intentos, a que se vuelva a activar ({@link EventDispatcher}).
 *
 * <p>La prueba es un evento de tipo {@code prueba}, firmado igual que los
 * reales y con el mismo sobre, pero sin datos de negocio: el receptor no tiene
 * nada que hacer con el. No pasa por la bandeja.
 */
@Service
public class SuscripcionesAdminService {

    /** El tipo del evento de prueba. No es parte del catalogo: no cuenta como un evento de negocio. */
    static final String PRUEBA = "prueba";

    private final SubscriptionRepository suscripciones;
    private final OutboxEventRepository bandeja;
    private final AdminLogRepository bitacora;
    private final ObjectMapper json;
    private final HttpClient http;

    @Autowired
    public SuscripcionesAdminService(SubscriptionRepository suscripciones, OutboxEventRepository bandeja,
                                     AdminLogRepository bitacora, ObjectMapper json) {
        this(suscripciones, bandeja, bitacora, json,
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build());
    }

    SuscripcionesAdminService(SubscriptionRepository suscripciones, OutboxEventRepository bandeja,
                              AdminLogRepository bitacora, ObjectMapper json, HttpClient http) {
        this.suscripciones = suscripciones;
        this.bandeja = bandeja;
        this.bitacora = bitacora;
        this.json = json;
        this.http = http;
    }

    /** Todas, por empresa. */
    @Transactional(readOnly = true)
    public List<SuscripcionAdminResponse> listar() {
        Map<Long, EntregasDeSuscripcion> entregas = bandeja.entregasPorSuscripcion().stream()
                .collect(Collectors.toMap(EntregasDeSuscripcion::suscripcion, Function.identity()));
        return suscripciones.findAll().stream()
                .sorted(Comparator.comparing((Subscription s) -> s.getOrganization().getTradeName())
                        .thenComparing(Subscription::getId))
                .map(s -> {
                    EntregasDeSuscripcion e = entregas.get(s.getId());
                    return new SuscripcionAdminResponse(s.getId(), s.getOrganization().getTradeName(),
                            s.getOrganization().getRut(), sinCredenciales(s.getUrl()), eventos(s), s.isActive(),
                            s.getCreatedAt(), e == null ? null : e.ultimaEntrega(),
                            e == null || e.pendientes() == null ? 0 : e.pendientes(),
                            e == null || e.fallidas() == null ? 0 : e.fallidas());
                })
                .toList();
    }

    @Transactional
    public void activar(Long id, String admin) {
        cambiarEstado(id, true, admin);
    }

    @Transactional
    public void desactivar(Long id, String admin) {
        cambiarEstado(id, false, admin);
    }

    /** Un secreto nuevo, guardado cifrado. Se devuelve esta unica vez. */
    @Transactional
    public SecretoNuevoResponse cambiarSecreto(Long id, String admin) {
        Subscription suscripcion = buscar(id);
        String secreto = EventosService.nuevoSecreto();
        suscripcion.setSecret(secreto);
        suscripciones.save(suscripcion);
        anotar(suscripcion, AdminLog.Action.suscripcion_secreto_cambiado, admin, "");
        return new SecretoNuevoResponse(id, secreto,
                "X-Firma: v1=HMAC-SHA256(secreto, X-Timestamp + \".\" + cuerpo), en hex");
    }

    /**
     * Manda el evento de prueba y cuenta lo que respondio el receptor. Tambien
     * a una desactivada: sirve para revisar el receptor antes de activarla.
     */
    @Transactional
    public PruebaSuscripcionResponse probar(Long id, String admin) {
        Subscription suscripcion = buscar(id);
        String eventoId = "evt_prueba_" + UUID.randomUUID();
        String cuerpo = aTexto(new EventoV1(eventoId, PRUEBA, "1", EventosService.enChile(Instant.now()),
                suscripcion.getOrganization().getRut(), null,
                Map.of("mensaje", "Evento de prueba enviado desde el panel de DataBridge. No hay que hacer nada con él.")));
        long marca = Instant.now().getEpochSecond();
        long inicio = System.nanoTime();
        PruebaSuscripcionResponse resultado;
        try {
            HttpRequest peticion = HttpRequest.newBuilder(URI.create(suscripcion.getUrl()))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .header("X-Evento", PRUEBA)
                    .header("X-Evento-Id", eventoId)
                    .header("X-Timestamp", String.valueOf(marca))
                    .header("X-Firma", EventDispatcher.firma(suscripcion.getSecret(), marca, cuerpo))
                    .POST(HttpRequest.BodyPublishers.ofString(cuerpo, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> respuesta = http.send(peticion, HttpResponse.BodyHandlers.ofString());
            String detalle = respuesta.body() == null ? "" : respuesta.body();
            resultado = new PruebaSuscripcionResponse(id, respuesta.statusCode() / 100 == 2, respuesta.statusCode(),
                    milisegundosDesde(inicio), detalle.isBlank() ? null : detalle.substring(0, Math.min(detalle.length(), 200)));
        } catch (InterruptedException corte) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Se interrumpió la prueba");
        } catch (Exception fallo) {
            resultado = new PruebaSuscripcionResponse(id, false, null, milisegundosDesde(inicio), motivoDe(fallo));
        }
        anotar(suscripcion, AdminLog.Action.suscripcion_probada, admin, resultado.estado() != null
                ? ", que respondió " + resultado.estado() : ", que no respondió");
        return resultado;
    }

    private void cambiarEstado(Long id, boolean activa, String admin) {
        Subscription suscripcion = buscar(id);
        if (suscripcion.isActive() == activa) {
            throw new ApiException(HttpStatus.CONFLICT, activa ? "Esa suscripción ya está activa"
                    : "Esa suscripción ya está desactivada");
        }
        suscripcion.setActive(activa);
        suscripciones.save(suscripcion);
        anotar(suscripcion, activa ? AdminLog.Action.suscripcion_activada : AdminLog.Action.suscripcion_desactivada,
                admin, "");
    }

    private Subscription buscar(Long id) {
        return suscripciones.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No hay ninguna suscripción con ese id"));
    }

    /** En la historia de la empresa: la suscripcion por su numero y su URL sin credenciales. */
    private void anotar(Subscription suscripcion, AdminLog.Action accion, String admin, String resto) {
        String sobre = "la suscripción " + suscripcion.getId() + " a " + sinCredenciales(suscripcion.getUrl()) + resto;
        //  La columna admite 254 caracteres, y una URL puede tener 300.
        bitacora.save(AdminLog.de(admin, accion, suscripcion.getOrganization().getRut(),
                sobre.length() <= 254 ? sobre : sobre.substring(0, 253) + "…"));
    }

    private List<String> eventos(Subscription suscripcion) {
        if (suscripcion.getEvents() == null || suscripcion.getEvents().isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(suscripcion.getEvents(), new TypeReference<List<String>>() { });
        } catch (JsonProcessingException e) {
            return List.of();
        }
    }

    /**
     * La URL sin usuario, clave, parametros ni fragmento: en la pantalla y en la
     * bitacora no se ve nada que sirva para entrar al receptor.
     */
    static String sinCredenciales(String url) {
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return "(dirección no válida)";
            }
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() >= 0 ? ":" + uri.getPort() : "")
                    + (uri.getRawPath() == null ? "" : uri.getRawPath());
        } catch (IllegalArgumentException e) {
            return "(dirección no válida)";
        }
    }

    /** Por que no hubo respuesta, en palabras: la pantalla lo muestra tal cual. */
    static String motivoDe(Exception fallo) {
        if (fallo instanceof HttpTimeoutException) {
            return "El receptor no respondió a tiempo";
        }
        if (fallo instanceof ConnectException) {
            return "No se pudo conectar con el receptor";
        }
        if (fallo instanceof IllegalArgumentException) {
            return "La dirección de la suscripción no es válida";
        }
        String detalle = fallo.getMessage() == null ? fallo.getClass().getSimpleName() : fallo.getMessage();
        return "Falló la conexión: " + detalle.substring(0, Math.min(detalle.length(), 160));
    }

    private static long milisegundosDesde(long inicio) {
        return Duration.ofNanos(System.nanoTime() - inicio).toMillis();
    }

    private String aTexto(Object valor) {
        try {
            return json.writeValueAsString(valor);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo armar el evento de prueba", e);
        }
    }
}
