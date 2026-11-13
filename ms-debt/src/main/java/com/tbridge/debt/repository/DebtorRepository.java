package com.tbridge.debt.repository;

import com.tbridge.debt.model.Debtor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface DebtorRepository extends JpaRepository<Debtor, Long> {

    Optional<Debtor> findByRut(String rut);

    /** Los deudores a los que el ultimo correo no les salio, el rechazo mas reciente primero (#437). */
    @Query("select r from Debtor r where r.emailFailedAt is not null order by r.emailFailedAt desc")
    List<Debtor> conCorreoFallido();
}
