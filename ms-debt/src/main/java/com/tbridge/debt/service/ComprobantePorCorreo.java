package com.tbridge.debt.service;

import com.tbridge.debt.client.AuthClient;
import com.tbridge.debt.client.CorreoNoSalio;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.DebtEvent;
import com.tbridge.debt.model.Debtor;
import com.tbridge.debt.repository.DebtEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * El comprobante de un pago, por correo al deudor (#541).
 *
 * <p>Cuando ms-debt aplica un pago confirmado, el deudor recibe el detalle del
 * pago y el comprobante en PDF, el mismo que descarga del portal. Si el pago
 * dejo la deuda pagada, tambien el certificado. El correo lo escribe y lo manda
 * ms-auth.
 *
 * <p>Sale despues del commit y en otro hilo: un pago que se deshace no manda
 * nada, y la pasarela no espera al servidor de correo. Un aviso repetido de la
 * pasarela no vuelve a aplicar el pago ({@link DebtService#onPagoConfirmado}),
 * asi que tampoco repite el correo. Si el correo falla, el pago ya quedo
 * aplicado: el comprobante sigue en el portal, y el motivo queda en el deudor,
 * como con cualquier correo que no llega.
 *
 * <p>No es cobranza: es la constancia de algo que hizo el deudor. Por eso sale
 * a cualquier hora y no cuenta para lo que deja la ley ({@link LimiteDeContacto}).
 */
@Service
public class ComprobantePorCorreo {

    private static final Logger log = LoggerFactory.getLogger(ComprobantePorCorreo.class);

    /** Lo que publica {@link DebtService#onPagoConfirmado}: este pago acaba de abonarse. */
    public record PagoAplicado(Long eventoId) {}

    private final DebtEventRepository eventos;
    private final DebtService deudas;
    private final ComprobanteService comprobantes;
    private final CertificateService certificados;
    private final AuthClient auth;

    public ComprobantePorCorreo(DebtEventRepository eventos, DebtService deudas, ComprobanteService comprobantes,
                                CertificateService certificados, AuthClient auth) {
        this.eventos = eventos;
        this.deudas = deudas;
        this.comprobantes = comprobantes;
        this.certificados = certificados;
        this.auth = auth;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enviar(PagoAplicado aviso) {
        DebtEvent evento = eventos.findById(aviso.eventoId())
                .filter(e -> e.getType() == DebtEvent.Type.payment_applied).orElse(null);
        if (evento == null) {
            return;
        }
        Debt deuda = evento.getDebt();
        Debtor deudor = deuda.getDebtor();
        if (deudor.getEmail() == null || deudor.getEmail().isBlank()) {
            //  Sin correo, el comprobante queda solo en el portal.
            return;
        }
        if (deudor.correoConProblemas()) {
            log.info("El comprobante del pago {} no se manda: la direccion de su deudor tiene problemas",
                    evento.getId());
            return;
        }
        AuthClient.PedidoDeComprobante pedido;
        try {
            pedido = pedido(evento, deuda, deudor);
        } catch (RuntimeException e) {
            log.warn("No se pudo armar el comprobante del pago {}: {}", evento.getId(), e.getMessage());
            return;
        }
        try {
            auth.enviarComprobante(pedido);
        } catch (CorreoNoSalio noSalio) {
            deudor.correoNoSalio(noSalio.motivo(), noSalio.deLaDireccion(), Instant.now());
            log.warn("El comprobante del pago {} no salio: {}", evento.getId(), noSalio.motivo());
            return;
        } catch (RuntimeException e) {
            log.warn("No se pudo mandar el comprobante del pago {}: {}", evento.getId(), e.getMessage());
            return;
        }
        deudor.correoSalio();
    }

    /** El correo: el detalle del comprobante, lo que queda, y los PDF. */
    private AuthClient.PedidoDeComprobante pedido(DebtEvent evento, Debt deuda, Debtor deudor) {
        ComprobanteService.Comprobante comprobante = comprobantes.armar(evento);
        List<AuthClient.Adjunto> adjuntos = new ArrayList<>();
        adjuntos.add(new AuthClient.Adjunto("comprobante-" + comprobante.folio() + ".pdf",
                comprobantes.pdf(comprobante)));
        //  Pagada por estado, como el certificado: una retirada tambien queda en cero.
        boolean saldada = deuda.getStatus() == Debt.Status.paid;
        if (saldada) {
            try {
                adjuntos.add(new AuthClient.Adjunto("certificado-" + deuda.getId() + ".pdf", certificados.de(deuda)));
            } catch (RuntimeException e) {
                //  El comprobante sale igual; el certificado se descarga del portal.
                log.warn("El certificado de la deuda {} no se pudo adjuntar: {}", deuda.getId(), e.getMessage());
            }
        }
        BigDecimal saldo = deudas.saldo(deuda);
        return new AuthClient.PedidoDeComprobante(deudor.getEmail(), String.valueOf(comprobante.folio()),
                comprobante.monto(), comprobante.nota(), comprobante.acreedor(),
                comprobante.filas().stream().map(f -> new AuthClient.Fila(f[0], f[1])).toList(),
                saldada, saldada ? null : DocumentoPdf.dinero(saldo, deuda.getCurrency()), adjuntos);
    }
}
