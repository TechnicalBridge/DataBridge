package com.tbridge.debt.repository;

import com.tbridge.debt.model.OutboxEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * De a cien, los mas antiguos primero: un atraso largo no se despacha de un
     * golpe. Solo los de suscripciones activas: una desactivada desde el panel
     * no recibe nada, y lo pendiente espera, sin gastar intentos, a que se
     * vuelva a activar (#438).
     */
    List<OutboxEvent> findTop100ByStatusAndNextAttemptAtBeforeAndSubscriptionActiveTrueOrderByIdAsc(
            OutboxEvent.Status status, Instant ahora);

    /** Lo que no se ha entregado, lo mas reciente primero, para el panel (#139). */
    List<OutboxEvent> findTop500ByStatusInOrderByIdDesc(Collection<OutboxEvent.Status> estados);

    long countByStatus(OutboxEvent.Status status);

    List<OutboxEvent> findBySubscriptionIdAndStatus(Long suscripcion, OutboxEvent.Status status);

    /** Como van las entregas de cada suscripcion, en una sola consulta, para el panel (#438). */
    @Query("select new com.tbridge.debt.repository.EntregasDeSuscripcion(e.subscription.id, max(e.deliveredAt), "
            + "sum(case when e.status = com.tbridge.debt.model.OutboxEvent.Status.pending then 1L else 0L end), "
            + "sum(case when e.status = com.tbridge.debt.model.OutboxEvent.Status.failed then 1L else 0L end)) "
            + "from OutboxEvent e group by e.subscription.id")
    List<EntregasDeSuscripcion> entregasPorSuscripcion();
}
