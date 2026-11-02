package com.tbridge.debt.repository;

import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.Repactation;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface RepactationRepository extends JpaRepository<Repactation, Long> {

    List<Repactation> findByDebtAndSupersededAtIsNull(Debt debt);

    /** Los convenios vigentes de varias deudas, en una sola consulta: la CAE de una lista. */
    List<Repactation> findByDebtInAndSupersededAtIsNull(Collection<Debt> debts);
}
