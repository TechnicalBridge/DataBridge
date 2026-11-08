package com.tbridge.debt.service;

import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.security.JwtPrincipal;
import com.tbridge.debt.dto.response.CodigoEnviadoResponse;
import com.tbridge.debt.model.Contact;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.Debtor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

/** Autoriza el reenvio manual y lo guarda en la misma cola durable.
 * El codigo nunca vuelve a la empresa. QUEUED/enviado=false significa solicitud
 * persistida; el contacto y los problemas del correo se actualizan al despachar.
 * El horario y el limite se controlan tanto aqui como al efectuar el envio.
 */
@Service
@Transactional
public class AccesoService {

    private final DebtService debts;
    private final ContactOutbox outbox;
    private final LimiteDeContacto limite;
    private final HorarioDeContacto horario;

    public AccesoService(DebtService debts, ContactOutbox outbox, LimiteDeContacto limite,
                         HorarioDeContacto horario) {
        this.debts = debts;
        this.outbox = outbox;
        this.limite = limite;
        this.horario = horario;
    }

    //  Un correo que no sale responde con un error, pero lo que se anoto en el
    //  deudor tiene que quedar: la cartera lo muestra.
    @Transactional(noRollbackFor = ApiException.class)
    public CodigoEnviadoResponse enviarCodigo(JwtPrincipal user, Long debtId) {
        if (user == null || !user.isCreditor()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "El codigo lo envia el acreedor, no el deudor");
        }
        Debt deuda = debts.requireVisible(user, debtId);
        if (deuda.getStatus() == Debt.Status.withdrawn || deuda.getStatus() == Debt.Status.paid) {
            throw new ApiException(HttpStatus.CONFLICT, "Esa deuda ya no esta en cobranza");
        }
        //  La empresa que la cobra esta suspendida (#152): al deudor no se le escribe. Puede verla
        //  otra empresa que siga activa, como el acreedor cuando la suspendida es su agencia.
        if (deuda.congelada()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "La empresa que cobra esta deuda está suspendida: por ahora no se le escribe al deudor");
        }
        Debtor deudor = deuda.getDebtor();
        if (deudor.getEmail() == null || deudor.getEmail().isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "El deudor no tiene correo registrado, y el envio por WhatsApp aun no esta conectado");
        }
        if (!horario.permiteAhora()) {
            throw new ApiException(HttpStatus.CONFLICT, "La ley solo deja escribirle a un deudor "
                    + HorarioDeContacto.REGLA + ". Intentalo dentro de ese horario");
        }
        if (!limite.permite(deudor, Instant.now())) {
            throw new ApiException(HttpStatus.CONFLICT, "Ya se le escribio lo que permite la ley: dos veces por "
                    + "semana y con dos dias entre una y otra. Intentalo en unos dias");
        }

        String destino = enmascarar(deudor.getEmail());
        if (!outbox.enqueue(Contact.reenvio(deuda, destino))) {
            throw new ApiException(HttpStatus.CONFLICT, "Ya hay un envio pendiente para este deudor");
        }
        return new CodigoEnviadoResponse(false, "correo", destino, null, "QUEUED");
    }

    /** felipe.rojas@correo.cl -> fe**********@correo.cl: se reconoce sin exponerlo. */
    static String enmascarar(String correo) {
        int arroba = correo.indexOf('@');
        if (arroba <= 2) {
            return "***" + correo.substring(Math.max(arroba, 0));
        }
        return correo.substring(0, 2) + "*".repeat(arroba - 2) + correo.substring(arroba);
    }
}
