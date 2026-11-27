package com.tbridge.debt.repository;
import com.tbridge.debt.model.EmailChange;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
public interface EmailChangeRepository extends JpaRepository<EmailChange, String> {
    @Modifying
    @Query("update EmailChange e set e.status = 'REPLACED' where e.debtorId = :debtor and e.status = 'PENDING'")
    int reemplazar(@Param("debtor") Long debtor);
    // Caller first locks the debtor, serializing requests, confirms and imports.
    Optional<EmailChange> findByIdAndDebtorId(String id, Long debtorId);
}
