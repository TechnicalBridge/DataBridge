package com.tbridge.debt.repository;
import com.tbridge.debt.model.ContactJob;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
public interface ContactJobRepository extends JpaRepository<ContactJob,Long> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<ContactJob> findByJobKey(String key);
    @Query("select j.id from ContactJob j where j.state = com.tbridge.debt.model.ContactJob.State.QUEUED order by j.updatedAt, j.id")
    List<Long> queued(org.springframework.data.domain.Pageable page);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from ContactJob j where j.id = :id")
    Optional<ContactJob> locked(@Param("id") Long id);
    @Query("select j.debt.debtor.id from ContactJob j where j.id = :id")
    Optional<Long> debtorId(@Param("id") Long id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from ContactJob j where j.debt.debtor.id = :debtor and j.state in :states")
    List<ContactJob> reserved(@Param("debtor") Long debtor, @Param("states") Collection<ContactJob.State> states);
    @Query("select count(j) from ContactJob j where j.debt.debtor.id = :debtor and j.state in :states")
    long pending(@Param("debtor") Long debtor, @Param("states") Collection<ContactJob.State> states);
    @Modifying
    @Query("update ContactJob j set j.state = com.tbridge.debt.model.ContactJob.State.UNCERTAIN "
            + "where j.state = com.tbridge.debt.model.ContactJob.State.SENDING and j.updatedAt < :before")
    int recover(@Param("before") java.time.Instant before);
}
