package com.tbridge.debt.repository;

import com.tbridge.debt.model.Mandate;
import com.tbridge.debt.model.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MandateRepository extends JpaRepository<Mandate, Long> {

    List<Mandate> findByAgencyAndCreditorAndStatus(
            Organization agency, Organization creditor, Mandate.Status status);

    List<Mandate> findByAgencyAndStatus(Organization agency, Mandate.Status status);

    /** Todos los de una agencia, con su acreedor: lo que ve en su portal (#234). */
    @Query("select m from Mandate m join fetch m.creditor where m.agency = :agencia order by m.validFrom desc, m.id desc")
    List<Mandate> deLaAgencia(@Param("agencia") Organization agencia);

    /** Uno de esa agencia: el de otra no existe para ella. */
    Optional<Mandate> findByIdAndAgency(Long id, Organization agency);

    /** Todos los mandatos con sus dos empresas ya cargadas: para el panel de administracion. */
    @Query("select m from Mandate m join fetch m.agency join fetch m.creditor order by m.validFrom desc")
    List<Mandate> todosConSusEmpresas();
}
