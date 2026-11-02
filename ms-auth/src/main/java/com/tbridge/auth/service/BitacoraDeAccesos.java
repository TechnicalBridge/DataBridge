package com.tbridge.auth.service;

import com.tbridge.auth.dto.response.AccesoResponse;
import com.tbridge.auth.dto.response.AccesosDeHoyResponse;
import com.tbridge.auth.exception.ApiException;
import com.tbridge.auth.model.AccessLog;
import com.tbridge.auth.repository.AccessLogRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * La bitacora de acceso, para el panel de administracion (#141): quien entro y
 * que accesos se rechazaron, para ver si alguien prueba codigos contra un RUT.
 *
 * <p>Se lee por pagina, lo mas reciente primero, porque crece con cada intento
 * de entrar. Los dias son de Chile, como en el resto del panel.
 */
@Service
public class BitacoraDeAccesos {

    static final int POR_PAGINA = 50;
    static final int MAXIMO_POR_PAGINA = 100;

    private static final ZoneId CHILE = ZoneId.of("America/Santiago");

    /** Lo que cuenta como rechazado en los totales del dia. */
    private static final EnumSet<AccessLog.Outcome> RECHAZOS = EnumSet.of(AccessLog.Outcome.invalid,
            AccessLog.Outcome.expired, AccessLog.Outcome.exhausted, AccessLog.Outcome.refused);

    private final AccessLogRepository bitacora;
    private final Clock reloj;

    @Autowired
    public BitacoraDeAccesos(AccessLogRepository bitacora) {
        this(bitacora, Clock.systemUTC());
    }

    BitacoraDeAccesos(AccessLogRepository bitacora, Clock reloj) {
        this.bitacora = bitacora;
        this.reloj = reloj;
    }

    /**
     * Una pagina de la bitacora. Sin fechas, desde el principio hasta hoy; con
     * ellas, los dos dias quedan incluidos. Sin tamano, 50 por pagina; nunca
     * mas de 100.
     */
    @Transactional(readOnly = true)
    public Page<AccesoResponse> buscar(LocalDate desde, LocalDate hasta, AccessLog.Method metodo,
                                       AccessLog.Outcome resultado, int pagina, int porPagina) {
        if (pagina < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "La pagina empieza en 0");
        }
        if (desde != null && hasta != null && hasta.isBefore(desde)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "'hasta' no puede ser antes de 'desde'");
        }
        int tamano = porPagina <= 0 ? POR_PAGINA : Math.min(porPagina, MAXIMO_POR_PAGINA);
        Instant inicio = desde == null ? Instant.EPOCH : desde.atStartOfDay(CHILE).toInstant();
        Instant fin = hasta == null
                ? reloj.instant().plus(1, ChronoUnit.DAYS)
                : hasta.plusDays(1).atStartOfDay(CHILE).toInstant();
        //  Por id ademas de la hora: dos intentos en el mismo instante no se
        //  cambian de pagina entre una consulta y la siguiente.
        PageRequest orden = PageRequest.of(pagina, tamano,
                Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id")));
        return bitacora.buscar(inicio, fin, metodo, resultado, orden).map(AccesoResponse::from);
    }

    /** Los totales de hoy, en Chile: cuantos entraron, cuantos rechazos y cuantos pedidos salieron. */
    @Transactional(readOnly = true)
    public AccesosDeHoyResponse hoy() {
        LocalDate dia = reloj.instant().atZone(CHILE).toLocalDate();
        Map<String, Long> porResultado = new LinkedHashMap<>();
        for (AccessLog.Outcome resultado : AccessLog.Outcome.values()) {
            porResultado.put(resultado.name(), 0L);
        }
        long rechazados = 0;
        for (AccessLogRepository.PorResultado fila
                : bitacora.contarPorResultadoDesde(dia.atStartOfDay(CHILE).toInstant())) {
            porResultado.put(fila.getResultado().name(), fila.getCantidad());
            if (RECHAZOS.contains(fila.getResultado())) {
                rechazados += fila.getCantidad();
            }
        }
        return new AccesosDeHoyResponse(dia, porResultado.get("granted"), rechazados, porResultado.get("sent"),
                porResultado);
    }
}
