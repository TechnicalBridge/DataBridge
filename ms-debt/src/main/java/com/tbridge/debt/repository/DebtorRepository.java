package com.tbridge.debt.repository;

import com.tbridge.debt.model.Debtor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface DebtorRepository extends JpaRepository<Debtor, Long> {

    Optional<Debtor> findByRut(String rut);

    /**
     * Los deudores que calzan con lo que busco el admin del panel, en todas las
     * empresas (#434): por el comienzo del RUT sin guion, o por un pedazo del
     * nombre, del correo o de la referencia de una de sus deudas. Las columnas
     * ya ignoran tildes y mayusculas. {@code texto} llega con sus % y _
     * escapados con '!', para que se busquen tal cual.
     */
    @Query("""
            select r from Debtor r
             where (:rut is not null and replace(r.rut, '-', '') like concat(:rut, '%'))
                or r.fullName like concat('%', :texto, '%') escape '!'
                or r.email like concat('%', :texto, '%') escape '!'
                or exists (select 1 from Debt d
                            where d.debtor = r and d.externalId like concat('%', :texto, '%') escape '!')
            """)
    Page<Debtor> buscar(@Param("rut") String rut, @Param("texto") String texto, Pageable pagina);
}
