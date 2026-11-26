package com.tbridge.debt.repository;

import com.tbridge.debt.model.ApiKey;
import com.tbridge.debt.model.Organization;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;

import java.util.List;
import java.util.Optional;

public interface ApiKeyRepository extends JpaRepository<ApiKey, Long> {

    @Modifying
    @Query("update ApiKey k set k.lastUsedAt = :ahora where k.id = :id "
            + "and k.revokedAt is null and (k.lastUsedAt is null or k.lastUsedAt <= :limite)")
    int registrarUsoSiVencido(@Param("id") Long id, @Param("ahora") Instant ahora,
                            @Param("limite") Instant limite);

    Optional<ApiKey> findByKeyHashAndRevokedAtIsNull(String keyHash);

    List<ApiKey> findByOrganizationOrderByCreatedAtDesc(Organization organization);
}
