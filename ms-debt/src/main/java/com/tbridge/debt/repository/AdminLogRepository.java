package com.tbridge.debt.repository;

import com.tbridge.debt.model.AdminLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AdminLogRepository extends JpaRepository<AdminLog, Long> {

    /** Lo que se hizo sobre una empresa desde el panel, lo mas reciente primero. */
    List<AdminLog> findByOrgRutOrderByOccurredAtDesc(String orgRut);

    @Query("""
            select r from AdminLog r
            where (:persona is null or lower(r.adminEmail) like concat('%', :persona, '%'))
              and (:accion is null or r.action = :accion)
              and (:empresa is null or r.orgRut = :empresa)
              and (:desde is null or r.occurredAt >= :desde)
              and (:hasta is null or r.occurredAt < :hasta)
            """)
    Page<AdminLog> buscar(@Param("persona") String persona, @Param("accion") AdminLog.Action accion,
                          @Param("empresa") String empresa, @Param("desde") Instant desde,
                          @Param("hasta") Instant hasta, Pageable pagina);
}
