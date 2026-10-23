package com.tbridge.auth.repository;

import com.tbridge.auth.model.Session;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

public interface SessionRepository extends JpaRepository<Session, Long> {

    Optional<Session> findByRefreshHash(String refreshHash);

    /**
     * La misma busqueda, pero bloqueando la fila hasta el fin de la
     * transaccion. Si dos pestanas renuevan en el mismo instante, sin el
     * bloqueo las dos leerian la llave sin rotar, las dos la rotarian, y la
     * familia quedaria con dos hijas vivas. Con el, la segunda espera, ve la
     * llave ya rotada, y recibe "reintenta".
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Session s where s.refreshHash = :huella")
    Optional<Session> bloquearPorHuella(@Param("huella") String huella);

    /** Revoca todas las llaves de una familia que sigan vivas. */
    @Modifying
    @Query("update Session s set s.revokedAt = :ahora "
            + "where s.familyId = :familia and s.revokedAt is null")
    int revocarFamilia(@Param("familia") String familia, @Param("ahora") Instant ahora);

    /**
     * Revoca todas las llaves vivas de una persona, de todas sus familias: es
     * lo que pasa cuando un admin le quita el acceso. Sin esto seguiria
     * adentro hasta que su sesion intentara renovarse.
     */
    @Modifying
    @Query("update Session s set s.revokedAt = :ahora "
            + "where lower(s.email) = lower(:correo) and s.revokedAt is null")
    int revocarDeCorreo(@Param("correo") String correo, @Param("ahora") Instant ahora);
}
