package com.tbridge.payments.service;

import com.tbridge.payments.dto.response.OperacionResponse;
import com.tbridge.payments.dto.response.UfResponse;
import com.tbridge.payments.exception.ApiException;
import com.tbridge.payments.model.AdminLog;
import com.tbridge.payments.model.DebtNotification;
import com.tbridge.payments.model.Payment;
import com.tbridge.payments.model.PaymentEvent;
import com.tbridge.payments.repository.AdminLogRepository;
import com.tbridge.payments.repository.DebtNotificationRepository;
import com.tbridge.payments.repository.PaymentEventRepository;
import com.tbridge.payments.repository.PaymentRepository;
import com.tbridge.payments.repository.UfValueRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;

/**
 * La operacion de los pagos, para la pantalla *Operacion* del panel (#139):
 * cuantos pagos esperan, la UF de hoy, los avisos a ms-debt sin entregar, y las
 * dos cosas que el admin puede hacer desde ahi, que quedan anotadas con quien
 * las hizo: volver a poner en cola un aviso, y cargar a mano la UF de un dia.
 */
@Service
public class OperacionService {

    private static final ZoneId CHILE = ZoneId.of("America/Santiago");
    private static final EnumSet<DebtNotification.Status> SIN_ENTREGAR =
            EnumSet.of(DebtNotification.Status.pending, DebtNotification.Status.failed);

    private final PaymentRepository pagos;
    private final PaymentEventRepository eventos;
    private final DebtNotificationRepository avisos;
    private final UfValueRepository ufs;
    private final UfService uf;
    private final AdminLogRepository bitacora;
    private final Clock reloj;

    @Autowired
    public OperacionService(PaymentRepository pagos, PaymentEventRepository eventos, DebtNotificationRepository avisos,
                            UfValueRepository ufs, UfService uf, AdminLogRepository bitacora) {
        this(pagos, eventos, avisos, ufs, uf, bitacora, Clock.systemUTC());
    }

    OperacionService(PaymentRepository pagos, PaymentEventRepository eventos, DebtNotificationRepository avisos,
                     UfValueRepository ufs, UfService uf, AdminLogRepository bitacora, Clock reloj) {
        this.pagos = pagos;
        this.eventos = eventos;
        this.avisos = avisos;
        this.ufs = ufs;
        this.uf = uf;
        this.bitacora = bitacora;
        this.reloj = reloj;
    }

    @Transactional(readOnly = true)
    public OperacionResponse operacion() {
        Instant ahora = reloj.instant();
        OperacionResponse.Pagos totales = new OperacionResponse.Pagos(
                pagos.countByStatus(Payment.Status.created),
                pagos.countByStatusAndCreatedAtBefore(Payment.Status.created, ahora.minus(Duration.ofHours(1))),
                eventos.countByTypeAndOccurredAtAfter(PaymentEvent.Type.expired, ahora.minus(Duration.ofHours(24))),
                pagos.countByStatus(Payment.Status.duplicated));

        LocalDate hoy = ahora.atZone(CHILE).toLocalDate();
        OperacionResponse.Uf ufDeHoy = ufs.findById(hoy)
                .map(v -> new OperacionResponse.Uf(hoy, true, v.getValue(), v.getSource()))
                .orElse(new OperacionResponse.Uf(hoy, false, null, null));

        OperacionResponse.Avisos sinEntregar = new OperacionResponse.Avisos(
                avisos.countByStatus(DebtNotification.Status.pending),
                avisos.countByStatus(DebtNotification.Status.failed),
                avisos.findTop200ByStatusInOrderByIdDesc(SIN_ENTREGAR).stream().map(OperacionService::aviso).toList());

        List<OperacionResponse.Accion> acciones = bitacora.findTop20ByOrderByOccurredAtDesc().stream()
                .map(r -> new OperacionResponse.Accion(r.getAction().name(), r.getSubject(), r.getAdminEmail(),
                        r.getOccurredAt()))
                .toList();

        return new OperacionResponse(totales, ufDeHoy, sinEntregar, acciones);
    }

    /** Vuelve a poner en cola un aviso de pago sin entregar, y anota quien lo hizo. */
    @Transactional
    public OperacionResponse.Aviso reintentarAviso(Long id, String admin) {
        DebtNotification aviso = avisos.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No hay ningun aviso con ese id"));
        if (aviso.getStatus() == DebtNotification.Status.delivered) {
            throw new ApiException(HttpStatus.CONFLICT, "Ese aviso ya se entrego");
        }
        aviso.reintentar();
        avisos.save(aviso);
        bitacora.save(AdminLog.de(admin, AdminLog.Action.aviso_reintentado, "el aviso del pago " + aviso.getPaymentId()));
        return aviso(aviso);
    }

    /**
     * Carga a mano la UF de un dia, por la misma via que la clave interna: queda
     * como la de ese dia y es la que usan los cobros en UF. Se anota quien la cargo.
     */
    @Transactional
    public UfResponse cargarUf(LocalDate dia, BigDecimal valor, String admin) {
        UfResponse cargada = uf.cargarAMano(dia, valor);
        bitacora.save(AdminLog.de(admin, AdminLog.Action.uf_cargada, cargada.dia() + ": " + cargada.valor()));
        return cargada;
    }

    private static OperacionResponse.Aviso aviso(DebtNotification aviso) {
        boolean pendiente = aviso.getStatus() == DebtNotification.Status.pending;
        return new OperacionResponse.Aviso(aviso.getId(), aviso.getPaymentId(), aviso.getStatus().name(),
                aviso.getAttempts(), pendiente ? aviso.getNextAttemptAt() : null, aviso.getLastError());
    }
}
