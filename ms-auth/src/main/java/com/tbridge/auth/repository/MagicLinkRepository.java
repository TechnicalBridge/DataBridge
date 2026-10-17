package com.tbridge.auth.repository;

import com.tbridge.auth.model.MagicLink;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface MagicLinkRepository extends JpaRepository<MagicLink, Long> {

    Optional<MagicLink> findByTokenHash(String tokenHash);

    /**
     * Da por usados los enlaces que una persona todavia no abrio. Los de un
     * deudor no se tocan: esos llevan su RUT.
     *
     * <p>Es para cuando un admin le quita el acceso a alguien: un enlace que
     * quedo en su correo no puede volver a servir si despues se lo devuelven.
     */
    @Modifying
    @Query("update MagicLink m set m.consumedAt = :ahora "
            + "where lower(m.email) = lower(:correo) and m.debtorRut is null and m.consumedAt is null")
    int anularPendientesDe(@Param("correo") String correo, @Param("ahora") Instant ahora);
}
