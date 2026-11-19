package com.tbridge.payments.repository;

import com.tbridge.payments.model.AdminLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AdminLogRepository extends JpaRepository<AdminLog, Long> {

    /** Lo ultimo que se hizo desde el panel con los pagos, lo mas reciente primero. */
    List<AdminLog> findTop20ByOrderByOccurredAtDesc();

    @Query("""
            select r from AdminLog r
            where (:persona is null or lower(r.adminEmail) like concat('%', :persona, '%'))
              and (:accion is null or r.action = :accion)
              and (:desde is null or r.occurredAt >= :desde)
              and (:hasta is null or r.occurredAt < :hasta)
            """)
    Page<AdminLog> buscar(@Param("persona") String persona, @Param("accion") AdminLog.Action accion,
                          @Param("desde") Instant desde, @Param("hasta") Instant hasta, Pageable pagina);
}
