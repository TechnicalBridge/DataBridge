package com.tbridge.debt.repository;

import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.DebtEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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

    @Query("select distinct e.debt.id from DebtEvent e where e.debt in :deudas "
            + "and e.type = :tipo and e.occurredAt > :desde")
    List<Long> deudasConEventoDesde(@Param("deudas") List<Debt> deudas,
                                   @Param("tipo") DebtEvent.Type tipo, @Param("desde") Instant desde);

    /**
     * Los eventos de un tipo entre dos instantes (desde incluido, hasta no), con su
     * deuda, quien la cargo y su campana, en una sola consulta: los pagos de un mes
     * para la comision de DataBridge (#200).
     */
    @Query("select e from DebtEvent e join fetch e.debt d join fetch d.lastBatch b join fetch b.sender "
            + "left join fetch d.campaign where e.type = :tipo and e.occurredAt >= :desde and e.occurredAt < :hasta")
    List<DebtEvent> entre(@Param("tipo") DebtEvent.Type tipo, @Param("desde") Instant desde,
                          @Param("hasta") Instant hasta);

    /**
     * El evento r cierra un reclamo de su deuda: la empresa la reanudo, o la
     * deuda salio de la cobranza (al resolverlo o en una carga), y antes hubo un
     * reclamo que nada cerro entre medio (#436).
     */
    String CIERRA_UN_RECLAMO = "and (r.type = :retirada or (r.type = :reanudada and r.reference = :rechazada)) "
            + "and exists (select a from DebtEvent a where a.debt = d and a.type = :abierto and a.id < r.id "
            + "and not exists (select b from DebtEvent b where b.debt = d and b.id > a.id and b.id < r.id "
            + "and (b.type = :retirada or (b.type = :reanudada and b.reference = :rechazada))))";

    /**
     * Los reclamos abiertos de todas las empresas: el ultimo reclamo de cada deuda
     * en revision, con su deuda, su acreedor y su campana, el mas antiguo primero
     * (#436).
     */
    @Query(value = "select e from DebtEvent e join fetch e.debt d join fetch d.creditor "
            + "left join fetch d.campaign c left join fetch c.agency "
            + "where e.type = :abierto and d.status = :enRevision "
            + "and e.id = (select max(x.id) from DebtEvent x where x.debt = d and x.type = :abierto) "
            + "order by e.occurredAt asc, e.id asc",
            countQuery = "select count(e) from DebtEvent e join e.debt d where e.type = :abierto "
                    + "and d.status = :enRevision "
                    + "and e.id = (select max(x.id) from DebtEvent x where x.debt = d and x.type = :abierto)")
    Page<DebtEvent> reclamosAbiertos(@Param("abierto") DebtEvent.Type abierto,
                                     @Param("enRevision") Debt.Status enRevision, Pageable pagina);

    /**
     * Lo que cerro un reclamo entre dos instantes (desde incluido, hasta no), con
     * su deuda, su acreedor y su campana, lo mas nuevo primero (#436).
     */
    @Query(value = "select r from DebtEvent r join fetch r.debt d join fetch d.creditor "
            + "left join fetch d.campaign c left join fetch c.agency "
            + "where r.occurredAt >= :desde and r.occurredAt < :hasta " + CIERRA_UN_RECLAMO
            + " order by r.occurredAt desc, r.id desc",
            countQuery = "select count(r) from DebtEvent r join r.debt d "
                    + "where r.occurredAt >= :desde and r.occurredAt < :hasta " + CIERRA_UN_RECLAMO)
    Page<DebtEvent> reclamosResueltos(@Param("abierto") DebtEvent.Type abierto,
                                      @Param("retirada") DebtEvent.Type retirada,
                                      @Param("reanudada") DebtEvent.Type reanudada,
                                      @Param("rechazada") String rechazada,
                                      @Param("desde") Instant desde, @Param("hasta") Instant hasta,
                                      Pageable pagina);

}
