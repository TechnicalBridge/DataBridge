package com.tbridge.payments.repository;

import com.tbridge.payments.model.PaymentEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface PaymentEventRepository extends JpaRepository<PaymentEvent, Long> {

    List<PaymentEvent> findByPaymentIdOrderByIdAsc(Long paymentId);

    /** Cuantas veces paso algo desde un instante: los pagos que vencieron en las ultimas 24 horas (#139). */
    long countByTypeAndOccurredAtAfter(PaymentEvent.Type type, Instant desde);
}
