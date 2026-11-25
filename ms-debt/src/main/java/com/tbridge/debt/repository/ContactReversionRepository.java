package com.tbridge.debt.repository;

import com.tbridge.debt.model.ContactReversion;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ContactReversionRepository extends JpaRepository<ContactReversion, String> {
    // Quien llama bloquea antes la ficha del deudor: los intentos no se pisan.
    List<ContactReversion> findByRutAndStatus(String rut, String status);

    /** Al deshacer un cambio, los otros codigos del mismo deudor dejan de servir. */
    @Modifying
    @Query("update ContactReversion r set r.status = 'CANCELLED', r.previousEmail = null "
            + "where r.debtorId = :debtor and r.status = 'PENDING' and r.id <> :usada")
    int cancelarOtras(@Param("debtor") Long debtor, @Param("usada") String usada);
}
