package com.tbridge.auth.repository;

import com.tbridge.auth.model.AccessCode;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import java.util.Optional;

public interface AccessCodeRepository extends JpaRepository<AccessCode, Long> {

    /**
     * El ultimo codigo emitido para ese RUT, tambien si ya se uso: consumir
     * uno nuevo no vuelve a habilitar uno anterior.
     *
     * Se busca por RUT y no por la huella del codigo porque hace falta poder
     * contar los intentos fallidos: si se buscara por huella, un codigo
     * equivocado simplemente no encontraria fila y probar al azar saldria
     * gratis.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<AccessCode> findFirstByDebtorRutOrderByIssuedAtDescIdDesc(String debtorRut);

    /** Saca un codigo recien emitido que nadie recibio. Dice cuantos saco. */
    long deleteByCodeHash(String codeHash);
}
