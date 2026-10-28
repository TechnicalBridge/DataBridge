package com.tbridge.debt.service;

import com.tbridge.debt.dto.response.AvisosResponse;
import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.model.AdminLog;
import com.tbridge.debt.model.OutboxEvent;
import com.tbridge.debt.model.Subscription;
import com.tbridge.debt.repository.AdminLogRepository;
import com.tbridge.debt.repository.OutboxEventRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Los avisos a las empresas que no se entregan, para el panel (#139): verlos por
 * destinatario y volver a ponerlos en cola.
 *
 * <p>Reintentar no entrega en el momento: deja el aviso pendiente para ahora, y
 * el despachador lo toma en su pasada siguiente (cada 15 segundos). Asi nunca
 * hay dos entregas del mismo aviso a la vez.
 */
@Service
public class AvisosService {

    private static final EnumSet<OutboxEvent.Status> SIN_ENTREGAR =
            EnumSet.of(OutboxEvent.Status.pending, OutboxEvent.Status.failed);

    private final OutboxEventRepository bandeja;
    private final AdminLogRepository bitacora;

    public AvisosService(OutboxEventRepository bandeja, AdminLogRepository bitacora) {
        this.bandeja = bandeja;
        this.bitacora = bitacora;
    }

    @Transactional(readOnly = true)
    public AvisosResponse sinEntregar() {
        Map<Long, List<OutboxEvent>> porDestino = new LinkedHashMap<>();
        for (OutboxEvent aviso : bandeja.findTop500ByStatusInOrderByIdDesc(SIN_ENTREGAR)) {
            porDestino.computeIfAbsent(aviso.getSubscription().getId(), k -> new ArrayList<>()).add(aviso);
        }
        List<AvisosResponse.Destino> destinos = new ArrayList<>();
        for (List<OutboxEvent> avisos : porDestino.values()) {
            Subscription suscripcion = avisos.getFirst().getSubscription();
            destinos.add(new AvisosResponse.Destino(suscripcion.getId(),
                    suscripcion.getOrganization().getTradeName(), suscripcion.getOrganization().getRut(),
                    direccion(suscripcion.getUrl()), contar(avisos, OutboxEvent.Status.pending),
                    contar(avisos, OutboxEvent.Status.failed), avisos.stream().map(AvisosService::aviso).toList()));
        }
        destinos.sort(Comparator.comparingLong(AvisosResponse.Destino::fallidos).reversed());
        return new AvisosResponse(bandeja.countByStatus(OutboxEvent.Status.pending),
                bandeja.countByStatus(OutboxEvent.Status.failed), destinos);
    }

    /** Vuelve a poner en cola un aviso sin entregar, y anota quien lo hizo. */
    @Transactional
    public AvisosResponse.Aviso reintentar(Long id, String admin) {
        OutboxEvent aviso = bandeja.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No hay ningun aviso con ese id"));
        if (aviso.getStatus() == OutboxEvent.Status.delivered) {
            throw new ApiException(HttpStatus.CONFLICT, "Ese aviso ya se entrego");
        }
        aviso.reintentar();
        bandeja.save(aviso);
        anotar(admin, aviso.getSubscription(), 1);
        return aviso(aviso);
    }

    /** Vuelve a poner en cola todos los fallidos de un destinatario. Cuantos fueron. */
    @Transactional
    public int reintentarDestino(Long suscripcion, String admin) {
        List<OutboxEvent> fallidos = bandeja.findBySubscriptionIdAndStatus(suscripcion, OutboxEvent.Status.failed);
        if (fallidos.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Ese destinatario no tiene avisos fallidos");
        }
        fallidos.forEach(OutboxEvent::reintentar);
        bandeja.saveAll(fallidos);
        anotar(admin, fallidos.getFirst().getSubscription(), fallidos.size());
        return fallidos.size();
    }

    private void anotar(String admin, Subscription suscripcion, int cuantos) {
        String sujeto = (cuantos == 1 ? "1 aviso" : cuantos + " avisos") + " a " + direccion(suscripcion.getUrl());
        bitacora.save(AdminLog.de(admin, AdminLog.Action.aviso_reintentado,
                suscripcion.getOrganization().getRut(), sujeto));
    }

    private static AvisosResponse.Aviso aviso(OutboxEvent aviso) {
        boolean pendiente = aviso.getStatus() == OutboxEvent.Status.pending;
        return new AvisosResponse.Aviso(aviso.getId(), aviso.getType(), aviso.getOccurredAt(),
                aviso.getStatus().name(), aviso.getAttempts(), pendiente ? aviso.getNextAttemptAt() : null,
                aviso.getLastError());
    }

    private static long contar(List<OutboxEvent> avisos, OutboxEvent.Status estado) {
        return avisos.stream().filter(a -> a.getStatus() == estado).count();
    }

    /**
     * Solo el esquema, el host y el puerto: una URL de suscripcion puede llevar
     * en la ruta o en los parametros algo que no tiene por que verse.
     */
    static String direccion(String url) {
        try {
            URI uri = URI.create(url);
            if (uri.getScheme() == null || uri.getHost() == null) {
                return "(direccion no valida)";
            }
            return uri.getScheme() + "://" + uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        } catch (IllegalArgumentException e) {
            return "(direccion no valida)";
        }
    }
}
