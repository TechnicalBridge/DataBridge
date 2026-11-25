package com.tbridge.debt.repository;

import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.DebtCharge;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface DebtChargeRepository extends JpaRepository<DebtCharge, Long> {

    List<DebtCharge> findByDebtOrderByDueDateAsc(Debt debt);

    List<DebtCharge> findByDebtInOrderByDueDateAsc(List<Debt> debts);

    void deleteByDebt(Debt debt);
}
