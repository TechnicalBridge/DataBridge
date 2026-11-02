package com.tbridge.debt.repository;

import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.DebtEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface DebtEventRepository extends JpaRepository<DebtEvent, Long> {

    List<DebtEvent> findByDebtOrderByOccurredAtAsc(Debt debt);

    List<DebtEvent> findByDebtInAndTypeAndOccurredAtAfter(Collection<Debt> debts, DebtEvent.Type type, Instant desde);

    List<DebtEvent> findByDebtIn(Collection<Debt> debts);

    List<DebtEvent> findByDebtInAndType(Collection<Debt> debts, DebtEvent.Type type);

    /** El historial de pagos: con un tope, porque es una pantalla y no un reporte. */
    List<DebtEvent> findTop300ByDebtInAndTypeOrderByOccurredAtDesc(Collection<Debt> debts, DebtEvent.Type type);

    boolean existsByDebtAndTypeAndOccurredAtAfter(Debt debt, DebtEvent.Type type, Instant desde);

    /**
     * Los eventos de un tipo entre dos instantes (desde incluido, hasta no), con su
     * deuda, quien la cargo y su campana, en una sola consulta: los pagos de un mes
     * para la comision de DataBridge (#200).
     */
    @Query("select e from DebtEvent e join fetch e.debt d join fetch d.lastBatch b join fetch b.sender "
            + "left join fetch d.campaign where e.type = :tipo and e.occurredAt >= :desde and e.occurredAt < :hasta")
    List<DebtEvent> entre(@Param("tipo") DebtEvent.Type tipo, @Param("desde") Instant desde,
                          @Param("hasta") Instant hasta);

}
