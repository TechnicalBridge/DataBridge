package com.tbridge.auth.repository;

import com.tbridge.auth.model.MagicLink;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface MagicLinkRepository extends JpaRepository<MagicLink, Long> {

    @Query("select coalesce(m.debtorRut, m.email) from MagicLink m where m.tokenHash = :hash")
    Optional<String> identidadDelToken(@Param("hash") String hash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<MagicLink> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<MagicLink> findFirstByDebtorRutAndCodeHashIsNotNullOrderByIssuedAtDescIdDesc(String rut);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<MagicLink> findFirstByEmailIgnoreCaseAndDebtorRutIsNullAndCodeHashIsNotNullOrderByIssuedAtDescIdDesc(
            String correo);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select m from MagicLink m where m.codeHash is not null and "
            + "((:rut is not null and m.debtorRut = :rut) or "
            + "(:rut is null and m.debtorRut is null and lower(m.email) = lower(:email))) "
            + "and m.consumedAt is null order by m.issuedAt desc, m.id desc")
    java.util.List<MagicLink> pendientes(@Param("rut") String rut, @Param("email") String email);

    @Modifying
    @Query("update MagicLink m set m.consumedAt = :ahora where m.debtorRut = :rut "
            + "and m.codeHash is not null and m.consumedAt is null")
    int anularPedidosDeRut(@Param("rut") String rut, @Param("ahora") Instant ahora);

    @Modifying
    @Query("update MagicLink m set m.consumedAt = :ahora where lower(m.email) = lower(:correo) "
            + "and m.debtorRut is null and m.codeHash is not null and m.consumedAt is null")
    int anularPedidosDeCorreo(@Param("correo") String correo, @Param("ahora") Instant ahora);

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
