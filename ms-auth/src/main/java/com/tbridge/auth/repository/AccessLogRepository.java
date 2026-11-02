package com.tbridge.auth.repository;

import com.tbridge.auth.model.AccessLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AccessLogRepository extends JpaRepository<AccessLog, Long> {

    /** Intentos recientes desde un mismo origen, para frenar fuerza bruta. */
    List<AccessLog> findByIpHashAndOccurredAtAfter(String ipHash, Instant desde);

    /**
     * La bitacora para el panel (#141): entre dos instantes y, si vienen, de un
     * metodo y con un resultado. El orden y el tamano de la pagina los pone
     * quien la pide.
     */
    @Query("""
            select a from AccessLog a
            where a.occurredAt >= :desde and a.occurredAt < :hasta
              and (:metodo is null or a.method = :metodo)
              and (:resultado is null or a.outcome = :resultado)
            """)
    Page<AccessLog> buscar(@Param("desde") Instant desde, @Param("hasta") Instant hasta,
                           @Param("metodo") AccessLog.Method metodo,
                           @Param("resultado") AccessLog.Outcome resultado, Pageable pagina);

    /** Cuantos hubo de cada resultado desde un instante: los totales del dia. */
    @Query("""
            select a.outcome as resultado, count(a) as cantidad from AccessLog a
            where a.occurredAt >= :desde
            group by a.outcome
            """)
    List<PorResultado> contarPorResultadoDesde(@Param("desde") Instant desde);

    interface PorResultado {
        AccessLog.Outcome getResultado();

        long getCantidad();
    }
}
