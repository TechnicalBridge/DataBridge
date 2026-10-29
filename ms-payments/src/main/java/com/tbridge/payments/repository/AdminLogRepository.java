package com.tbridge.payments.repository;

import com.tbridge.payments.model.AdminLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AdminLogRepository extends JpaRepository<AdminLog, Long> {

    /** Lo ultimo que se hizo desde el panel con los pagos, lo mas reciente primero. */
    List<AdminLog> findTop20ByOrderByOccurredAtDesc();
}
