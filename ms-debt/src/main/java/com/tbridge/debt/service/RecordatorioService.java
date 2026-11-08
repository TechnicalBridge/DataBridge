package com.tbridge.debt.service;

import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.dto.response.RecordatoriosResponse;
import com.tbridge.debt.model.Contact;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.Debtor;
import com.tbridge.debt.model.Installment;
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

/** Selecciona cuotas por recordar sin esperar al proveedor.
 * Cada intencion se persiste en el outbox; el trabajador vuelve a comprobar
 * preferencias, estado de cuota, horario y limite antes del envio.
 * Solo la aceptacion del proveedor crea Contact.recordatorio.
 */
@Service
public class RecordatorioService {

    private static final Logger log = LoggerFactory.getLogger(RecordatorioService.class);
    private static final ZoneId CHILE = HorarioDeContacto.CHILE;
    private static final List<Debt.Status> EN_COBRANZA = List.of(Debt.Status.open, Debt.Status.repacted);

    private final InstallmentRepository installments;
    private final ContactOutbox outbox;
    private final LimiteDeContacto limite;
    private final HorarioDeContacto horario;
    private final int diasAntes;

    public RecordatorioService(InstallmentRepository installments, ContactOutbox outbox,
                               LimiteDeContacto limite, HorarioDeContacto horario,
                               @Value("${app.recordatorios.dias-antes:3}") int diasAntes) {
        this.installments = installments;
        this.outbox = outbox;
        this.limite = limite;
        this.horario = horario;
        this.diasAntes = diasAntes;
    }

    @Scheduled(cron = "${app.recordatorios.cron:0 0 9 * * *}", zone = "America/Santiago")
    @Transactional
    public void programado() {
        if (!horario.permiteAhora()) {
            //  Tambien si alguien movio el cron fuera del horario.
            log.info("Recordatorios de cuota: hoy, a esta hora, la ley no deja escribirle a nadie");
            return;
        }
        RecordatoriosResponse pasada = enviar(LocalDate.now(CHILE));
        if (pasada.enviados() + pasada.omitidos() > 0) {
            log.info("Recordatorios de cuota: {} encolados, {} omitidos", pasada.enviados(), pasada.omitidos());
        }
    }

    /** Una pasada, como si hoy fuera {@code hoy}. */
    @Transactional
    public RecordatoriosResponse enviar(LocalDate hoy) {
        if (!horario.esDiaDeContacto(hoy)) {
            return new RecordatoriosResponse(0, 0);
        }
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
            if (outbox.enqueue(Contact.recordatorio(cuota, AccesoService.enmascarar(deudor.getEmail())))) enviados++;
            else omitidos++;
        }
        return new RecordatoriosResponse(enviados, omitidos);
    }
}
