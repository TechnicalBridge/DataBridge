package com.tbridge.payments.service;

import com.tbridge.payments.dto.response.PagoAdminDetalleResponse;
import com.tbridge.payments.dto.response.PagoAdminResponse;
import com.tbridge.payments.dto.response.PagoVerificadoResponse;
import com.tbridge.payments.dto.response.PaymentEventResponse;
import com.tbridge.payments.exception.ApiException;
import com.tbridge.payments.model.AdminLog;
import com.tbridge.payments.model.Payment;
import com.tbridge.payments.repository.AdminLogRepository;
import com.tbridge.payments.repository.PaymentEventRepository;
import com.tbridge.payments.repository.PaymentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * La pantalla Pagos del panel (#435): los pagos de todas las empresas con
 * filtros, el detalle de uno con su historia, y volver a preguntarle a la
 * pasarela por uno que sigue abierto. No muestra datos del deudor: eso va en
 * su ficha (#434). Verificar queda en la bitacora del panel.
 */
@Service
public class PagosAdminService {

    private static final ZoneId CHILE = ZoneId.of("America/Santiago");
    private static final int MAXIMO_POR_PAGINA = 50;
    private static final Pattern RUT = Pattern.compile("\\d{1,8}-[\\dK]");
    private static final Map<Payment.Status, String> COMO_QUEDO = Map.of(
            Payment.Status.created, "sigue abierto",
            Payment.Status.paid, "quedó pagado",
            Payment.Status.failed, "quedó fallido",
            Payment.Status.expired, "quedó vencido",
            Payment.Status.duplicated, "quedó duplicado");

    private final PaymentRepository pagos;
    private final PaymentEventRepository eventos;
    private final AdminLogRepository bitacora;
    private final PaymentService cobros;
    private final Clock reloj;

    @Autowired
    public PagosAdminService(PaymentRepository pagos, PaymentEventRepository eventos, AdminLogRepository bitacora,
                             PaymentService cobros) {
        this(pagos, eventos, bitacora, cobros, Clock.systemUTC());
    }

    PagosAdminService(PaymentRepository pagos, PaymentEventRepository eventos, AdminLogRepository bitacora,
                      PaymentService cobros, Clock reloj) {
        this.pagos = pagos;
        this.eventos = eventos;
        this.bitacora = bitacora;
        this.cobros = cobros;
        this.reloj = reloj;
    }

    /**
     * Una pagina de pagos, el mas nuevo primero. Las fechas son dias de Chile y
     * quedan incluidas. Con {@code revisar}, solo los abiertos hace mas de una
     * hora y los duplicados.
     */
    @Transactional(readOnly = true)
    public Page<PagoAdminResponse> buscar(String estado, String pasarela, String empresa, LocalDate desde,
                                          LocalDate hasta, boolean revisar, int pagina, int porPagina) {
        if (pagina < 0 || porPagina < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "La página y su tamaño deben ser positivos");
        }
        if (desde != null && hasta != null && hasta.isBefore(desde)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "'hasta' no puede ser anterior a 'desde'");
        }
        Instant ahora = reloj.instant();
        PageRequest orden = PageRequest.of(pagina, Math.min(porPagina, MAXIMO_POR_PAGINA),
                Sort.by(Sort.Order.desc("createdAt"), Sort.Order.desc("id")));
        return pagos.paraElPanel(valor(Payment.Status.class, estado, "estado"),
                        valor(Payment.Gateway.class, pasarela, "pasarela"), rut(empresa),
                        desde == null ? null : desde.atStartOfDay(CHILE).toInstant(),
                        hasta == null ? null : hasta.plusDays(1).atStartOfDay(CHILE).toInstant(),
                        revisar, ahora.minus(PagoAdminResponse.ABIERTO_DE_MAS), orden)
                .map(p -> PagoAdminResponse.de(p, ahora));
    }

    /** Un pago con todo lo que le paso. */
    @Transactional(readOnly = true)
    public PagoAdminDetalleResponse detalle(Long id) {
        Payment pago = pagos.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No hay ningún pago con ese id"));
        return new PagoAdminDetalleResponse(PagoAdminResponse.de(pago, reloj.instant()),
                eventos.findByPaymentIdOrderByIdAsc(id).stream().map(PaymentEventResponse::from).toList());
    }

    /**
     * Le vuelve a preguntar a la pasarela por un pago abierto. Si ya cobro, se
     * confirma por el camino de siempre (y ms-debt recibe el aviso una vez). Queda
     * en la bitacora con como quedo.
     */
    @Transactional
    public PagoVerificadoResponse verificar(Long id, String admin) {
        Payment pago = cobros.verificarComoAdmin(id);
        bitacora.save(AdminLog.de(admin, AdminLog.Action.pago_verificado, "el pago " + pago.getId() + " de "
                + pago.getGateway().name() + ", que " + COMO_QUEDO.getOrDefault(pago.getStatus(),
                pago.getStatus().name())));
        return new PagoVerificadoResponse(pago.getId(), pago.getGateway(), Payment.Status.created, pago.getStatus());
    }

    private static <E extends Enum<E>> E valor(Class<E> tipo, String crudo, String que) {
        if (crudo == null || crudo.isBlank()) {
            return null;
        }
        try {
            return Enum.valueOf(tipo, crudo.strip());
        } catch (IllegalArgumentException desconocido) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "No existe ese " + que + ": " + crudo.strip());
        }
    }

    /** El RUT de la empresa como lo guarda ms-payments, o null si no se filtra por empresa. */
    private static String rut(String crudo) {
        if (crudo == null || crudo.isBlank()) {
            return null;
        }
        String normalizado = PagosDelDeudorService.normalizar(crudo);
        if (!RUT.matcher(normalizado).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RUT de empresa no válido");
        }
        return normalizado.toUpperCase(Locale.ROOT);
    }
}
