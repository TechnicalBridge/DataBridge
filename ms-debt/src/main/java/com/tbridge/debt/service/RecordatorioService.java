package com.tbridge.debt.service;

import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.client.AuthClient;
import com.tbridge.debt.client.CorreoNoSalio;
import com.tbridge.debt.dto.response.RecordatoriosResponse;
import com.tbridge.debt.model.Contact;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.Debtor;
import com.tbridge.debt.model.Installment;
import com.tbridge.debt.repository.ContactRepository;
import com.tbridge.debt.repository.InstallmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

/**
 * El correo que le recuerda al deudor una cuota por vencer.
 *
 * <p>Pasa una vez al dia y avisa las cuotas que vencen dentro de
 * {@code app.recordatorios.dias-antes} dias. El correo lo manda ms-auth, con un
 * codigo de acceso nuevo, y no lleva ni el monto ni un enlace: por la misma
 * razon que el primer aviso, un correo que llega a quien no es no debe decir
 * cuanto debe alguien ni a quien, ni llevarlo a ninguna parte.
 *
 * <p>Cada cuota se avisa una sola vez: que ya se aviso es que existe su
 * contacto, y la base no deja que haya dos. Si ms-auth no responde, o el
 * correo no sale, no queda ninguno y el aviso sale en la pasada siguiente, al
 * otro dia. Si la culpa es del servidor de correo, la pasada se corta ahi.
 */
@Service
public class RecordatorioService {

    private static final Logger log = LoggerFactory.getLogger(RecordatorioService.class);
    private static final ZoneId CHILE = ZoneId.of("America/Santiago");
    private static final List<Debt.Status> EN_COBRANZA = List.of(Debt.Status.open, Debt.Status.repacted);

    private final InstallmentRepository installments;
    private final ContactRepository contactos;
    private final AuthClient auth;
    private final LimiteDeContacto limite;
    private final int diasAntes;

    public RecordatorioService(InstallmentRepository installments, ContactRepository contactos, AuthClient auth,
                               LimiteDeContacto limite,
                               @Value("${app.recordatorios.dias-antes:3}") int diasAntes) {
        this.installments = installments;
        this.contactos = contactos;
        this.auth = auth;
        this.limite = limite;
        this.diasAntes = diasAntes;
    }

    @Scheduled(cron = "${app.recordatorios.cron:0 0 9 * * *}", zone = "America/Santiago")
    @Transactional
    public void programado() {
        RecordatoriosResponse pasada = enviar(LocalDate.now(CHILE));
        if (pasada.enviados() + pasada.omitidos() > 0) {
            log.info("Recordatorios de cuota: {} enviados, {} omitidos", pasada.enviados(), pasada.omitidos());
        }
    }

    /** Una pasada, como si hoy fuera {@code hoy}. */
    @Transactional
    public RecordatoriosResponse enviar(LocalDate hoy) {
        int enviados = 0;
        int omitidos = 0;
        Set<Debtor> fallaron = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Installment cuota : installments.porRecordar(Installment.Status.pending, hoy, hoy.plusDays(diasAntes),
                EN_COBRANZA)) {
            Debt deuda = cuota.getDebt();
            Debtor deudor = deuda.getDebtor();
            if (!deudor.isReminders() || deudor.getEmail() == null || deudor.getEmail().isBlank()
                    || deudor.correoConProblemas() || fallaron.contains(deudor)) {
                omitidos++;
                continue;
            }
            if (!limite.permite(deudor, Instant.now())) {
                //  La ley no deja escribirle hoy: la cuota queda sin avisar y el
                //  aviso sale en una pasada siguiente.
                omitidos++;
                continue;
            }
            try {
                auth.emitirCodigo(new AuthClient.PedidoDeCodigo(deudor.getRut(), List.of("correo"),
                        deudor.getEmail(), deuda.getCreditor().getTradeName(), deuda.getExternalId(),
                        cuota.getDueDate()));
            } catch (CorreoNoSalio noSalio) {
                deudor.correoNoSalio(noSalio.motivo(), noSalio.deLaDireccion(), Instant.now());
                omitidos++;
                if (!noSalio.deLaDireccion()) {
                    log.warn("Recordatorios: la pasada se corta, el correo no sale: {}", noSalio.motivo());
                    return new RecordatoriosResponse(enviados, omitidos);
                }
                fallaron.add(deudor);
                continue;
            } catch (ApiException e) {
                log.warn("No se pudo recordar la cuota {} de {}: {}", cuota.getId(), deuda.getExternalId(),
                        e.getMessage());
                omitidos++;
                continue;
            }
            contactos.save(Contact.recordatorio(cuota, AccesoService.enmascarar(deudor.getEmail())));
            deudor.correoSalio();
            enviados++;
        }
        return new RecordatoriosResponse(enviados, omitidos);
    }
}
