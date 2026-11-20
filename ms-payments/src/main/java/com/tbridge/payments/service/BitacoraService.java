package com.tbridge.payments.service;

import com.tbridge.payments.dto.response.BitacoraResponse;
import com.tbridge.payments.exception.ApiException;
import com.tbridge.payments.model.AdminLog;
import com.tbridge.payments.repository.AdminLogRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/** Consulta paginada y filtrada de la bitacora administrativa de ms-payments (#443). */
@Service
public class BitacoraService {
    private static final ZoneId CHILE = ZoneId.of("America/Santiago");
    private final AdminLogRepository registros;

    public BitacoraService(AdminLogRepository registros) {
        this.registros = registros;
    }

    @Transactional(readOnly = true)
    public Page<BitacoraResponse> buscar(String persona, String accion, String empresa,
                                         LocalDate desde, LocalDate hasta, int pagina, int porPagina) {
        Pageable orden = pagina(pagina, porPagina);
        if (desde != null && hasta != null && hasta.isBefore(desde)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "'hasta' no puede ser anterior a 'desde'");
        }
        AdminLog.Action accionValida = accion(accion);
        if ((hay(accion) && accionValida == null) || hay(empresa)) return Page.empty(orden);
        Instant inicio = desde == null ? null : desde.atStartOfDay(CHILE).toInstant();
        Instant fin = hasta == null ? null : hasta.plusDays(1).atStartOfDay(CHILE).toInstant();
        return registros.buscar(texto(persona), accionValida, inicio, fin, orden).map(BitacoraResponse::from);
    }

    private static Pageable pagina(int numero, int tamano) {
        if (numero < 0 || tamano < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "La pagina y su tamaño deben ser positivos");
        }
        return PageRequest.of(numero, Math.min(tamano, 100),
                Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id")));
    }

    private static AdminLog.Action accion(String valor) {
        if (!hay(valor)) return null;
        try { return AdminLog.Action.valueOf(valor.strip()); }
        catch (IllegalArgumentException desconocida) { return null; }
    }

    private static String texto(String valor) { return hay(valor) ? valor.strip().toLowerCase() : null; }
    private static boolean hay(String valor) { return valor != null && !valor.isBlank(); }
}
