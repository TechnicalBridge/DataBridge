package com.tbridge.debt.service;

import com.tbridge.debt.model.Contact;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.Debtor;
import com.tbridge.debt.repository.DebtRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.Set;

/** Selecciona invitaciones y persiste intenciones en la transaccion del lote/pasada.
 * El listener AFTER_COMMIT existente se conserva. El despacho remoto, el tope por
 * cuarto de hora y el contacto confirmado viven en ContactDispatcher.
 * Las pendientes sin contacto se seleccionan nuevamente; la llave durable evita duplicados.
 */
@Service
public class InvitacionService {

    private static final Logger log = LoggerFactory.getLogger(InvitacionService.class);

    /** Lo que publica la ingesta: esta deuda acaba de entrar, o de volver, a cobranza. */
    public record DeudaEnCobranza(Long debtId) {}

    /** Lo que hizo una pasada por las invitaciones pendientes, y si se corto antes de terminar. */
    public record Pasada(int enviadas, boolean cortada) {}

    private final DebtRepository debts;
    private final ContactOutbox outbox;
    private final LimiteDeContacto limite;
    private final HorarioDeContacto horario;

    public InvitacionService(DebtRepository debts, ContactOutbox outbox,
                             LimiteDeContacto limite, HorarioDeContacto horario) {
        this.debts = debts;
        this.outbox = outbox;
        this.limite = limite;
        this.horario = horario;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void invitar(DeudaEnCobranza aviso) {
        Debt deuda = debts.findById(aviso.debtId()).orElse(null);
        if (deuda == null || deuda.getStatus() != Debt.Status.open) {
            return;
        }
        if (deuda.congelada()) {
            //  La empresa que la cobra esta suspendida (#152): queda sin invitar,
            //  y la invita la primera pasada despues de que la reactiven
            //  (invitarPendientes).
            log.info("La deuda {} se invita cuando reactiven a la empresa que la cobra", deuda.getId());
            return;
        }
        Debtor deudor = deuda.getDebtor();
        if (deudor.getEmail() == null || deudor.getEmail().isBlank()) {
            //  Sin correo no hay por donde: el aviso por WhatsApp aun no esta
            //  conectado. La deuda queda sin invitar, y el portal lo muestra.
            return;
        }
        if (deudor.correoConProblemas()) {
            log.info("La deuda {} no se invita: la direccion de su deudor tiene problemas", deuda.getId());
            return;
        }
        if (!horario.permiteAhora()) {
            //  De noche, un domingo o un feriado: la invita la primera pasada
            //  de contacto dentro del horario (invitarPendientes).
            log.info("La deuda {} se invita en la proxima pasada: ahora no es horario de cobranza", deuda.getId());
            return;
        }
        if (!limite.permite(deudor, Instant.now())) {
            //  Esta semana ya se le escribio lo que deja la ley: la campana lo
            //  invita en cuanto se pueda.
            log.info("La deuda {} se invita despues: el deudor ya recibio lo que deja la ley esta semana",
                    deuda.getId());
            return;
        }
        outbox.enqueue(Contact.invitacion(deuda, AccesoService.enmascarar(deudor.getEmail())));
    }

    /**
     * Invita a las deudas en cobranza que todavia no recibieron ningun correo.
     * Corre en cada pasada de contacto, que ya es dentro del horario de la
     * ley, y respeta el limite de la semana. El resultado cuenta intenciones encoladas, no aceptaciones del proveedor.
     *
     * @param fallaron los deudores a los que ya no se les intenta en esta
     *                 pasada, porque su correo acaba de fallar. Se agregan los
     *                 de aca.
     */
    @Transactional
    public Pasada invitarPendientes(Instant cuando, Set<Debtor> fallaron) {
        int enviadas = 0;
        for (Debt deuda : debts.sinInvitar(Debtor.RECHAZOS_PARA_DEJAR)) {
            Debtor deudor = deuda.getDebtor();
            if (fallaron.contains(deudor) || !limite.permite(deudor, cuando)) {
                continue;
            }
            if (outbox.enqueue(Contact.invitacion(deuda, AccesoService.enmascarar(deudor.getEmail())))) enviadas++;
        }
        return new Pasada(enviadas, false);
    }

}
