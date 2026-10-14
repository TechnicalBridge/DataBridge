package com.tbridge.debt.repository;

import com.tbridge.debt.model.Mandate;
import com.tbridge.debt.model.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;

public interface MandateRepository extends JpaRepository<Mandate, Long> {

    List<Mandate> findByAgencyAndCreditorAndStatus(
            Organization agency, Organization creditor, Mandate.Status status);

    List<Mandate> findByAgencyAndStatus(Organization agency, Mandate.Status status);

    /** Todos los mandatos con sus dos empresas ya cargadas: para el panel de administracion. */
    @Query("select m from Mandate m join fetch m.agency join fetch m.creditor order by m.validFrom desc")
    List<Mandate> todosConSusEmpresas();
}
