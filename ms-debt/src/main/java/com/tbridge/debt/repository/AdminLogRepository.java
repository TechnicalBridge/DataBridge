package com.tbridge.debt.repository;

import com.tbridge.debt.model.AdminLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdminLogRepository extends JpaRepository<AdminLog, Long> {

    /** Lo que se hizo sobre una empresa desde el panel, lo mas reciente primero. */
    List<AdminLog> findByOrgRutOrderByOccurredAtDesc(String orgRut);
}
