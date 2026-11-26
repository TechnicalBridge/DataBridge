package com.tbridge.auth.repository;

import com.tbridge.auth.model.AdminLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface AdminLogRepository extends JpaRepository<AdminLog, Long> {

    /** Lo que se hizo con las personas de una empresa, lo mas reciente primero. */
    List<AdminLog> findByOrgRutOrderByOccurredAtDesc(String orgRut);

    /** Lo que se hizo con los admin de DataBridge, que no son de ninguna empresa. */
    List<AdminLog> findByOrgRutIsNullOrderByOccurredAtDesc();

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
