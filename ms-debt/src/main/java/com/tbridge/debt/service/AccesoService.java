package com.tbridge.debt.service;

import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.security.JwtPrincipal;
import com.tbridge.debt.client.AuthClient;
import com.tbridge.debt.client.CorreoNoSalio;
import com.tbridge.debt.dto.response.CodigoEnviadoResponse;
import com.tbridge.debt.model.Contact;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.Debtor;
import com.tbridge.debt.repository.ContactRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Hacerle llegar al deudor su codigo de acceso.
 *
 * <p>Es lo que hara el motor de campana cuando exista: la cartera llega, la
 * campana dice por que canales, y a cada deudor le llega su codigo. Mientras
 * tanto lo dispara a mano el personal de la empresa, deuda por deuda.
 *
 * <p><b>El codigo nunca vuelve a quien lo pidio.</b> ms-auth lo genera y lo
 * manda al correo del deudor; esta respuesta solo dice a donde se mando.
 *
 * <p>Si el correo no sale, la empresa lo sabe en el momento ({@code 502}, con
 * el motivo). La empresa puede insistir aunque la direccion tenga problemas:
 * lo que se deja de hacer solo, se puede hacer a mano.
 */
@Service
@Transactional
public class AccesoService {

    private final DebtService debts;
    private final ContactRepository contactos;
    private final AuthClient auth;
    private final LimiteDeContacto limite;

    public AccesoService(DebtService debts, ContactRepository contactos, AuthClient auth, LimiteDeContacto limite) {
        this.debts = debts;
        this.contactos = contactos;
        this.auth = auth;
        this.limite = limite;
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
        Debtor deudor = deuda.getDebtor();
        if (deudor.getEmail() == null || deudor.getEmail().isBlank()) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "El deudor no tiene correo registrado, y el envio por WhatsApp aun no esta conectado");
        }
        if (!limite.permite(deudor, Instant.now())) {
            throw new ApiException(HttpStatus.CONFLICT, "Ya se le escribio lo que permite la ley: dos veces por "
                    + "semana y con dos dias entre una y otra. Intentalo en unos dias");
        }

        AuthClient.CodigoEmitido emitido;
        try {
            emitido = auth.emitirCodigo(new AuthClient.PedidoDeCodigo(
                    deudor.getRut(), List.of("correo"), deudor.getEmail(), deuda.getCreditor().getTradeName(),
                    deuda.getExternalId()));
        } catch (CorreoNoSalio noSalio) {
            deudor.correoNoSalio(noSalio.motivo(), noSalio.deLaDireccion(), Instant.now());
            throw noSalio;
        }

        //  Queda como un contacto de la deuda: es el primer paso del embudo que
        //  la agencia mide (contrato, campana.avance), y cuenta para el limite.
        String destino = enmascarar(deudor.getEmail());
        contactos.save(Contact.reenvio(deuda, destino));
        deudor.correoSalio();

        return new CodigoEnviadoResponse(true, "correo", destino, emitido == null ? null : emitido.expiraEn());
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
