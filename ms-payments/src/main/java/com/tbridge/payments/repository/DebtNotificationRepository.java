package com.tbridge.payments.repository;

import com.tbridge.payments.model.DebtNotification;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface DebtNotificationRepository extends JpaRepository<DebtNotification, Long> {

    /** Lo que no se ha entregado, lo mas reciente primero, para el panel (#139). */
    List<DebtNotification> findTop200ByStatusInOrderByIdDesc(Collection<DebtNotification.Status> estados);

    long countByStatus(DebtNotification.Status status);

    /** Lo pendiente que ya toca reintentar. Por aca entra el despachador. */
    List<DebtNotification> findByStatusAndNextAttemptAtBefore(
            DebtNotification.Status status, Instant limite);

    Optional<DebtNotification> findByPaymentId(Long paymentId);
}
