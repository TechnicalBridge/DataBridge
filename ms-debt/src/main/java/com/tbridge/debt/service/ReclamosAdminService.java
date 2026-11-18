package com.tbridge.debt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.debt.dto.response.ReclamosAdminResponse;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.DebtEvent;
import com.tbridge.debt.repository.DebtEventRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Transactional(readOnly = true)
public class ReclamosAdminService {
    private static final ZoneId CHILE = ZoneId.of("America/Santiago");
    private final DebtEventRepository eventos;
    private final ObjectMapper json;
    private final Clock reloj;

    @Autowired
    public ReclamosAdminService(DebtEventRepository eventos, ObjectMapper json) {
        this(eventos, json, Clock.systemUTC());
    }

    ReclamosAdminService(DebtEventRepository eventos, ObjectMapper json, Clock reloj) {
        this.eventos = eventos;
        this.json = json;
        this.reloj = reloj;
    }

    public ReclamosAdminResponse consultar(LocalDate desde, LocalDate hasta) {
        LocalDate hoy = LocalDate.now(reloj.withZone(CHILE));
        LocalDate fin = hasta == null ? hoy : hasta;
        LocalDate inicio = desde == null ? fin.withDayOfMonth(1) : desde;
        if (fin.isBefore(inicio)) {
            throw new com.tbridge.debt.exception.ApiException(org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Hasta debe ser posterior o igual a desde");
        }

        List<DebtEvent> abiertos = eventos.reclamosAbiertos();
        Map<Long, DebtEvent> ultimo = new HashMap<>();
        for (DebtEvent evento : abiertos) {
            ultimo.merge(evento.getDebt().getId(), evento, (a, b) ->
                    a.getOccurredAt().isAfter(b.getOccurredAt()) ? a : b);
        }
        Instant ahora = reloj.instant();
        List<ReclamosAdminResponse.Reclamo> pendientes = ultimo.values().stream()
                .sorted((a, b) -> a.getOccurredAt().compareTo(b.getOccurredAt()))
                .map(e -> fila(e, null, ahora)).toList();

        List<DebtEvent> resoluciones = eventos.reclamosResueltos(inicio.atStartOfDay(CHILE).toInstant(),
                fin.plusDays(1).atStartOfDay(CHILE).toInstant());
        List<Debt> deudas = resoluciones.stream().map(DebtEvent::getDebt).distinct().toList();
        Map<Long, List<DebtEvent>> reclamaciones = new HashMap<>();
        if (!deudas.isEmpty()) {
            for (DebtEvent e : eventos.findByDebtInAndType(deudas, DebtEvent.Type.disputed)) {
                reclamaciones.computeIfAbsent(e.getDebt().getId(), ignored -> new java.util.ArrayList<>()).add(e);
            }
        }
        List<ReclamosAdminResponse.Reclamo> resueltos = resoluciones.stream().map(resolucion -> {
            DebtEvent reclamo = reclamaciones.getOrDefault(resolucion.getDebt().getId(), List.of()).stream()
                    .filter(e -> !e.getOccurredAt().isAfter(resolucion.getOccurredAt()))
                    .max((a, b) -> a.getOccurredAt().compareTo(b.getOccurredAt())).orElse(null);
            return fila(reclamo, resolucion, ahora);
        }).toList();
        return new ReclamosAdminResponse(pendientes, resueltos);
    }

    private ReclamosAdminResponse.Reclamo fila(DebtEvent reclamacion, DebtEvent resolucion, Instant ahora) {
        DebtEvent evento = resolucion == null ? reclamacion : resolucion;
        Debt deuda = evento.getDebt();
        String motivo = reclamacion == null ? null : reclamacion.getReference();
        Instant fecha = reclamacion == null ? null : reclamacion.getOccurredAt();
        Instant termino = resolucion == null ? null : resolucion.getOccurredAt();
        long dias = fecha == null ? 0 : Math.max(0, ChronoUnit.DAYS.between(
                fecha.atZone(CHILE).toLocalDate(), (termino == null ? ahora : termino).atZone(CHILE).toLocalDate()));
        return new ReclamosAdminResponse.Reclamo(deuda.getId(), deuda.getExternalId(),
                deuda.getLastBatch().getSender().getTradeName(), motivo, campo(reclamacion, "detalle"),
                fecha, dias, termino,
                resolucion == null ? null : resolucion.getType() == DebtEvent.Type.withdrawn ? "retirado" : "reanudado",
                campo(resolucion, "nota"));
    }

    private String campo(DebtEvent evento, String campo) {
        if (evento == null || evento.getDetail() == null) return null;
        try {
            JsonNode valor = json.readTree(evento.getDetail()).get(campo);
            return valor == null || valor.isNull() ? null : valor.asText();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("El detalle de la disputa no es JSON válido", e);
        }
    }
}
