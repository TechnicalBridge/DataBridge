package com.tbridge.debt.repository;

import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.Installment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

public interface InstallmentRepository extends JpaRepository<Installment, Long> {

    List<Installment> findByDebtOrderByNumberAsc(Debt debt);

    List<Installment> findByDebtAndStatus(Debt debt, Installment.Status status);

    /**
     * Las cuotas por recordar: pendientes, que vencen entre manana y el dia
     * indicado, de deudas que siguen en cobranza, y a las que todavia no se
     * les mando el aviso, que es no tener su contacto. Lo de "entre" y no
     * "ese dia" es a proposito: si la tarea no corrio un dia, al siguiente se
     * pone al dia.
     */
    @Query("""
            select c from Installment c join fetch c.debt d join fetch d.debtor join fetch d.creditor
             where c.status = :pendiente
               and not exists (select 1 from Contact k where k.installment = c)
               and c.dueDate > :hoy and c.dueDate <= :hasta
               and d.status in :enCobranza
             order by c.dueDate""")
    List<Installment> porRecordar(@Param("pendiente") Installment.Status pendiente,
                                  @Param("hoy") LocalDate hoy,
                                  @Param("hasta") LocalDate hasta,
                                  @Param("enCobranza") Collection<Debt.Status> enCobranza);
}
