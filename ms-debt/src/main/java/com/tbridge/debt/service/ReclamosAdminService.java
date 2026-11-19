package com.tbridge.debt.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.debt.dto.response.ReclamoAdminResponse;
import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.DebtEvent;
import com.tbridge.debt.repository.DebtEventRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Los reclamos de todas las empresas para el panel (#436): los abiertos, el mas
 * antiguo primero, con los dias que llevan sin respuesta, y los que se cerraron
 * en un periodo y como. Sin datos del deudor. El panel solo mira: el reclamo lo
 * resuelve la empresa que cobra.
 */
@Service
public class ReclamosAdminService {

    private static final ZoneId CHILE = ZoneId.of("America/Santiago");
    private static final int MAXIMO_POR_PAGINA = 50;
    /** Sin fechas, los resueltos son los de los ultimos 30 dias, hoy incluido. */
    static final int DIAS_POR_OMISION = 30;
    /** Lo que anota DisputaService cuando la empresa dice que la deuda si corresponde. */
    static final String RECHAZADA = "disputa_rechazada";

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

    /** Los reclamos abiertos, el mas antiguo primero. */
    @Transactional(readOnly = true)
    public Page<ReclamoAdminResponse> abiertos(int pagina, int porPagina) {
        LocalDate hoy = LocalDate.now(reloj.withZone(CHILE));
        return eventos.reclamosAbiertos(DebtEvent.Type.disputed, Debt.Status.disputed, orden(pagina, porPagina))
                .map(e -> respuesta(e, null, ChronoUnit.DAYS.between(diaDe(e.getOccurredAt()), hoy)));
    }

    /** Los reclamos que se cerraron entre dos dias de Chile, ambos incluidos, lo mas nuevo primero. */
    @Transactional(readOnly = true)
    public Page<ReclamoAdminResponse> resueltos(LocalDate desde, LocalDate hasta, int pagina, int porPagina) {
        LocalDate fin = hasta != null ? hasta : LocalDate.now(reloj.withZone(CHILE));
        LocalDate inicio = desde != null ? desde : fin.minusDays(DIAS_POR_OMISION - 1);
        if (inicio.isAfter(fin)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "La fecha desde no puede ser posterior a hasta");
        }
        Page<DebtEvent> cierres = eventos.reclamosResueltos(DebtEvent.Type.disputed, DebtEvent.Type.withdrawn,
                DebtEvent.Type.updated, RECHAZADA, inicio.atStartOfDay(CHILE).toInstant(),
                fin.plusDays(1).atStartOfDay(CHILE).toInstant(), orden(pagina, porPagina));
        //  El reclamo de cada cierre: el ultimo de su deuda antes del cierre. La
        //  consulta ya aseguro que existe y que nada lo cerro antes.
        Map<Long, List<DebtEvent>> reclamos = cierres.isEmpty() ? Map.of()
                : eventos.findByDebtInAndType(cierres.getContent().stream().map(DebtEvent::getDebt).distinct().toList(),
                        DebtEvent.Type.disputed).stream().collect(Collectors.groupingBy(e -> e.getDebt().getId()));
        return cierres.map(cierre -> {
            DebtEvent reclamo = reclamos.getOrDefault(cierre.getDebt().getId(), List.of()).stream()
                    .filter(e -> e.getId() < cierre.getId())
                    .max(Comparator.comparing(DebtEvent::getId))
                    .orElseThrow(() -> new IllegalStateException("El cierre " + cierre.getId() + " no tiene reclamo"));
            return respuesta(reclamo, cierre,
                    ChronoUnit.DAYS.between(diaDe(reclamo.getOccurredAt()), diaDe(cierre.getOccurredAt())));
        });
    }

    private ReclamoAdminResponse respuesta(DebtEvent reclamo, DebtEvent cierre, long dias) {
        Debt deuda = reclamo.getDebt();
        boolean retirada = cierre != null && cierre.getType() == DebtEvent.Type.withdrawn;
        return new ReclamoAdminResponse(deuda.getId(), deuda.getExternalId(), deuda.getConcept(),
                deuda.getCreditor().getTradeName(),
                deuda.getCampaign() != null ? deuda.getCampaign().getAgency().getTradeName()
                        : deuda.getCreditor().getTradeName(),
                deuda.getCampaign() == null ? null : deuda.getCampaign().getName(),
                reclamo.getReference(), campo(reclamo, "detalle"), reclamo.getOccurredAt(), dias,
                cierre == null ? null : cierre.getOccurredAt(),
                cierre == null ? null : retirada ? "retirada" : "reanudada",
                retirada ? cierre.getReference() : null,
                cierre == null ? null : campo(cierre, "nota"));
    }

    /** Un campo de texto del detalle del evento, o null si no lo tiene. */
    private String campo(DebtEvent evento, String nombre) {
        if (evento.getDetail() == null) {
            return null;
        }
        try {
            return json.readTree(evento.getDetail()).path(nombre).asText(null);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static LocalDate diaDe(Instant instante) {
        return instante.atZone(CHILE).toLocalDate();
    }

    private static PageRequest orden(int pagina, int porPagina) {
        if (pagina < 0 || porPagina < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "La página y su tamaño deben ser positivos");
        }
        return PageRequest.of(pagina, Math.min(porPagina, MAXIMO_POR_PAGINA));
    }
}
