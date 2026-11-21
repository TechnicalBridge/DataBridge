package com.tbridge.debt.repository;
import com.tbridge.debt.model.ContactNotice;
import org.springframework.data.jpa.repository.*;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
public interface ContactNoticeRepository extends JpaRepository<ContactNotice, String> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<ContactNotice> findTop20ByDeliveredAtIsNullAndAttemptsLessThanAndNextAttemptAtBeforeOrderByNextAttemptAtAsc(int attempts, Instant now);
}
