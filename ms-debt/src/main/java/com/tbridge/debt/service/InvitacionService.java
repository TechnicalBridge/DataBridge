package com.tbridge.debt.service;

import com.tbridge.debt.client.AuthClient;
import com.tbridge.debt.client.CorreoNoSalio;
import com.tbridge.debt.model.Contact;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.Debtor;
import com.tbridge.debt.repository.ContactRepository;
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
import java.util.List;
import java.util.Set;

/**
 * La invitacion: el primer codigo de acceso, apenas una deuda entra en cobranza.
 *
 * <p>DataBridge detecta al moroso cuando llega la cartera, y con esto el deudor
 * se entera sin que nadie de la agencia tenga que apretar un boton. El correo
 * es el primer aviso de siempre, sin monto ni enlace. El boton "Enviar codigo"
 * del portal sigue ahi para reenviarlo.
 *
 * <p>Sale despues del commit del lote y en otro hilo: un lote que se deshace no
 * invita a nadie, y mil deudas nuevas no hacen esperar a quien entrega la
 * cartera. Si ms-auth no responde, la deuda queda igual y la agencia envia el
 * codigo a mano.
 *
 * <p>Fuera del horario de la ley ({@link HorarioDeContacto}) no sale: una
 * cartera que llega de noche o un domingo deja sus deudas sin invitar, y las
 * invita la primera pasada de contacto dentro del horario.
 *
 * <p>Si el correo no sale, no queda como enviado: el motivo queda en el
 * deudor. Cada pasada de contacto invita a las deudas que todavia no
 * recibieron ningun correo ({@link #invitarPendientes}): asi, corregido el
 * servidor de correo, la invitacion sale sola, y tambien la que la ley dejo
 * para despues o la de un deudor que recien trajo correo.
 *
 * <p>Salen a lo mas las que caben en cada cuarto de hora
 * ({@link TopeDeInvitaciones}): una carga de mil deudores no manda mil correos
 * en el mismo minuto, y lo que pasa del tope sale en las pasadas siguientes.
 */
@Service
public class InvitacionService {

    private static final Logger log = LoggerFactory.getLogger(InvitacionService.class);

    /** Lo que publica la ingesta: esta deuda acaba de entrar, o de volver, a cobranza. */
    public record DeudaEnCobranza(Long debtId) {}

    /** Lo que hizo una pasada por las invitaciones pendientes, y si se corto antes de terminar. */
    public record Pasada(int enviadas, boolean cortada) {}

    private final DebtRepository debts;
    private final ContactRepository contactos;
    private final AuthClient auth;
    private final LimiteDeContacto limite;
    private final HorarioDeContacto horario;
    private final TopeDeInvitaciones tope;

    public InvitacionService(DebtRepository debts, ContactRepository contactos, AuthClient auth,
                             LimiteDeContacto limite, HorarioDeContacto horario, TopeDeInvitaciones tope) {
        this.debts = debts;
        this.contactos = contactos;
        this.auth = auth;
        this.limite = limite;
        this.horario = horario;
        this.tope = tope;
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void invitar(DeudaEnCobranza aviso) {
        Debt deuda = debts.findById(aviso.debtId()).orElse(null);
        if (deuda == null || deuda.getStatus() != Debt.Status.open) {
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
        if (!tope.tomar()) {
            //  Una carga grande: lo que pasa del tope lo invitan las pasadas siguientes.
            log.info("La deuda {} se invita en la proxima pasada: ya salieron {} invitaciones en este cuarto de hora",
                    deuda.getId(), tope.maximo());
            return;
        }
        try {
            auth.emitirCodigo(pedido(deuda));
        } catch (CorreoNoSalio noSalio) {
            //  No es un contacto: queda en el deudor, y la invita la pasada siguiente.
            deudor.correoNoSalio(noSalio.motivo(), noSalio.deLaDireccion(), Instant.now());
            log.warn("La invitacion de la deuda {} no salio: {}", deuda.getId(), noSalio.motivo());
            return;
        } catch (RuntimeException e) {
            log.warn("No se pudo invitar al deudor de la deuda {}: {}", deuda.getId(), e.getMessage());
            return;
        }
        contactos.save(Contact.invitacion(deuda, AccesoService.enmascarar(deudor.getEmail())));
        deudor.correoSalio();
    }

    /**
     * Invita a las deudas en cobranza que todavia no recibieron ningun correo.
     * Corre en cada pasada de contacto, que ya es dentro del horario de la
     * ley, y respeta el limite de la semana. Si el servidor de correo no
     * acepta, se corta: lo que falta sale en la pasada siguiente.
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
            if (!tope.tomar()) {
                //  Las que faltan salen en la pasada siguiente. No se corta la pasada: las campanas siguen.
                log.info("Invitaciones: {} en esta pasada, y las demas en la siguiente (tope de {} cada 15 minutos)",
                        enviadas, tope.maximo());
                return new Pasada(enviadas, false);
            }
            try {
                auth.emitirCodigo(pedido(deuda));
            } catch (CorreoNoSalio noSalio) {
                deudor.correoNoSalio(noSalio.motivo(), noSalio.deLaDireccion(), cuando);
                fallaron.add(deudor);
                if (!noSalio.deLaDireccion()) {
                    return new Pasada(enviadas, true);
                }
                continue;
            } catch (RuntimeException e) {
                log.warn("Las invitaciones pendientes quedan para la pasada siguiente: {}", e.getMessage());
                return new Pasada(enviadas, true);
            }
            contactos.save(Contact.invitacion(deuda, AccesoService.enmascarar(deudor.getEmail())).el(cuando));
            deudor.correoSalio();
            enviadas++;
        }
        return new Pasada(enviadas, false);
    }

    private static AuthClient.PedidoDeCodigo pedido(Debt deuda) {
        return new AuthClient.PedidoDeCodigo(deuda.getDebtor().getRut(), List.of("correo"),
                deuda.getDebtor().getEmail(), deuda.getCreditor().getTradeName(), deuda.getExternalId());
    }
}
