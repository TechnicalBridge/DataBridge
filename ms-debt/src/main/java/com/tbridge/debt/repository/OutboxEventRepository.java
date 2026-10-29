package com.tbridge.debt.repository;

import com.tbridge.debt.model.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /** De a cien, los mas antiguos primero: un atraso largo no se despacha de un golpe. */
    List<OutboxEvent> findTop100ByStatusAndNextAttemptAtBeforeOrderByIdAsc(
            OutboxEvent.Status status, Instant ahora);

    /** Lo que no se ha entregado, lo mas reciente primero, para el panel (#139). */
    List<OutboxEvent> findTop500ByStatusInOrderByIdDesc(Collection<OutboxEvent.Status> estados);

    long countByStatus(OutboxEvent.Status status);

    List<OutboxEvent> findBySubscriptionIdAndStatus(Long suscripcion, OutboxEvent.Status status);
}
