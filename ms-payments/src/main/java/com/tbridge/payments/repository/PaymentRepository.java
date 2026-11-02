package com.tbridge.payments.repository;

import com.tbridge.payments.model.Payment;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** Lo que ve un deudor: lo suyo, por RUT. */
    List<Payment> findByDebtorRutOrderByCreatedAtDesc(String debtorRut);

    /** El pago de una pasarela por su id de transaccion (en Khipu, el payment_id). */
    Optional<Payment> findByGatewayAndGatewayTxnId(Payment.Gateway gateway, String gatewayTxnId);

    /** Los pagos de una pasarela en un estado: los cobros de Khipu que siguen abiertos. */
    List<Payment> findByGatewayAndStatus(Payment.Gateway gateway, Payment.Status status);

    /** Los de una pasarela en un estado, abiertos despues de una fecha: los vencidos del ultimo dia. */
    List<Payment> findByGatewayAndStatusAndCreatedAtAfter(Payment.Gateway gateway, Payment.Status status,
                                                          Instant desde);

    /**
     * El pago, con su fila bloqueada hasta que termine la transaccion. Lo usan
     * la vuelta de Webpay y la consulta periodica antes de confirmar: si los
     * dos llegan a la vez, el segundo espera, ve el pago ya cerrado y no hace
     * nada. Sin el bloqueo los dos lo confirmaban y el segundo chocaba.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.id = :id")
    Optional<Payment> paraCerrar(@Param("id") Long id);

    /** Lo mismo, por el token de la pasarela: la vuelta de Webpay. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.gateway = :gateway and p.gatewayTxnId = :token")
    Optional<Payment> paraCerrarPorToken(@Param("gateway") Payment.Gateway gateway, @Param("token") String token);

    /** Los pagos de una deuda en un estado: los abiertos, o los ya pagados. */
    List<Payment> findByDebtIdAndStatus(Long debtId, Payment.Status status);

    /** Cuantos pagos hay en un estado: los totales de la pantalla Operacion (#139). */
    long countByStatus(Payment.Status status);

    /** Cuantos siguen en un estado desde antes de un instante: los abiertos que llevan rato. */
    long countByStatusAndCreatedAtBefore(Payment.Status status, Instant antes);

    /** Los pagos en un estado de varias deudas: los duplicados de una cartera. */
    List<Payment> findByStatusAndDebtIdInOrderByCreatedAtDesc(Payment.Status status, Collection<Long> debtIds);

    /**
     * Lo que ve un acreedor: SOLO lo suyo.
     *
     * El modelo anterior devolvia findAll() para cualquier acreedor, asi que
     * todos veian los pagos de todos. No era un descuido del codigo: no habia
     * columna por la que filtrar.
     */
    List<Payment> findByCreditorRutOrderByCreatedAtDesc(String creditorRut);
}
