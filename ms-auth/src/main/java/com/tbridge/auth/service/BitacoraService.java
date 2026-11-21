package com.tbridge.auth.service;

import com.tbridge.auth.dto.response.BitacoraResponse;
import com.tbridge.auth.exception.ApiException;
import com.tbridge.auth.model.AdminLog;
import com.tbridge.auth.repository.AdminLogRepository;
import com.tbridge.auth.util.Rut;
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

/** Consulta paginada y filtrada de la bitacora administrativa de ms-auth (#443). */
@Service
public class BitacoraService {

    private static final ZoneId CHILE = ZoneId.of("America/Santiago");
    private static final int MAXIMO_POR_PAGINA = 100;
    private final AdminLogRepository registros;

    public BitacoraService(AdminLogRepository registros) {
        this.registros = registros;
    }

    @Transactional(readOnly = true)
    public Page<BitacoraResponse> buscar(String persona, String accion, String empresa,
                                         LocalDate desde, LocalDate hasta, int pagina, int porPagina) {
        Pageable orden = pagina(pagina, porPagina);
        validarFechas(desde, hasta);
        AdminLog.Action accionValida = accion(accion);
        if (hay(accion) && accionValida == null) {
            return Page.empty(orden);
        }
        String rut = empresa(empresa);
        Instant inicio = desde == null ? null : desde.atStartOfDay(CHILE).toInstant();
        Instant fin = hasta == null ? null : hasta.plusDays(1).atStartOfDay(CHILE).toInstant();
        return registros.buscar(texto(persona), accionValida, rut, inicio, fin, orden).map(BitacoraResponse::from);
    }

    private static Pageable pagina(int pagina, int porPagina) {
        if (pagina < 0 || porPagina < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "La página y su tamaño deben ser positivos");
        }
        return PageRequest.of(pagina, Math.min(porPagina, MAXIMO_POR_PAGINA),
                Sort.by(Sort.Order.desc("occurredAt"), Sort.Order.desc("id")));
    }

    private static void validarFechas(LocalDate desde, LocalDate hasta) {
        if (desde != null && hasta != null && hasta.isBefore(desde)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "'hasta' no puede ser anterior a 'desde'");
        }
    }

    private static AdminLog.Action accion(String valor) {
        if (!hay(valor)) return null;
        try {
            return AdminLog.Action.valueOf(valor.strip());
        } catch (IllegalArgumentException desconocida) {
            return null;
        }
    }

    private static String empresa(String valor) {
        if (!hay(valor)) return null;
        String rut = Rut.normalizar(valor);
        if (!Rut.esValido(rut)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RUT de empresa no valido");
        }
        return rut;
    }

    private static String texto(String valor) {
        return hay(valor) ? valor.strip().toLowerCase() : null;
    }

    private static boolean hay(String valor) {
        return valor != null && !valor.isBlank();
    }
}
