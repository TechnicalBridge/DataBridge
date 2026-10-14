package com.tbridge.auth.repository;

import com.tbridge.auth.model.AccessCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AccessCodeRepository extends JpaRepository<AccessCode, Long> {

    /**
     * El ultimo codigo sin usar de ese RUT.
     *
     * Se busca por RUT y no por la huella del codigo porque hace falta poder
     * contar los intentos fallidos: si se buscara por huella, un codigo
     * equivocado simplemente no encontraria fila y probar al azar saldria
     * gratis.
     */
    Optional<AccessCode> findFirstByDebtorRutAndConsumedAtIsNullOrderByIssuedAtDesc(String debtorRut);

    /** Saca un codigo recien emitido que nadie recibio. Dice cuantos saco. */
    long deleteByCodeHash(String codeHash);
}
