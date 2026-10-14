package com.tbridge.auth.repository;

import com.tbridge.auth.model.AdminLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdminLogRepository extends JpaRepository<AdminLog, Long> {

    /** Lo que se hizo con las personas de una empresa, lo mas reciente primero. */
    List<AdminLog> findByOrgRutOrderByOccurredAtDesc(String orgRut);

    /** Lo que se hizo con los admin de DataBridge, que no son de ninguna empresa. */
    List<AdminLog> findByOrgRutIsNullOrderByOccurredAtDesc();
}
