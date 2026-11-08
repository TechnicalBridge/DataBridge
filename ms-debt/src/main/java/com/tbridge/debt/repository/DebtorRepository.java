package com.tbridge.debt.repository;

import com.tbridge.debt.model.Debtor;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface DebtorRepository extends JpaRepository<Debtor, Long> {

    @org.springframework.data.jpa.repository.Lock(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE)
    @org.springframework.data.jpa.repository.Query("select d from Debtor d where d.id = :id")
    Optional<Debtor> locked(@org.springframework.data.repository.query.Param("id") Long id);

    Optional<Debtor> findByRut(String rut);
}
