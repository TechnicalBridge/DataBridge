package com.tbridge.debt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.debt.client.AuthClient;
import com.tbridge.debt.client.CorreoNoSalio;
import com.tbridge.debt.dto.response.ContactosResponse;
import com.tbridge.debt.model.Campaign;
import com.tbridge.debt.model.Contact;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.Debtor;
import com.tbridge.debt.repository.CampaignRepository;
import com.tbridge.debt.repository.ContactRepository;
import com.tbridge.debt.repository.DebtRepository;
import com.tbridge.debt.repository.ToquesHechos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * La campana, ejecutada: APOFYX decide la estrategia (canales, intentos,
 * cadencia y fechas) y DataBridge la cumple.
 *
 * <p>Cada toque es el mismo correo de la invitacion: un codigo para entrar al
 * portal, sin el monto ni un enlace. El toque {@code k} de una deuda sale el
 * dia {@code entrada + cadencia[k]}, donde la entrada es cuando la deuda llego
 * a DataBridge (o el inicio de la campana, si empezo despues). Sale mientras:
 *
 * <ul>
 *   <li>la campana este en curso, entre su inicio y su fin, y contacte por correo;</li>
 *   <li>la deuda siga abierta: si se paga, se repacta, se reclama o se retira,
 *       la campana la deja. El convenio tiene sus propios recordatorios;</li>
 *   <li>no se hayan hecho todos los intentos;</li>
 *   <li>la ley lo permita: dentro de {@link HorarioDeContacto} y no mas de lo
 *       que deja {@link LimiteDeContacto}. Un toque que la ley no deja hoy sale
 *       en la proxima pasada en que si.</li>
 * </ul>
 *
 * <p>Un toque cuyo correo no sale no es un toque: queda el motivo en el
 * deudor y se intenta de nuevo en la pasada siguiente. Si la culpa es del
 * servidor de correo, la pasada se corta ahi, en vez de esperarlo una vez por
 * deuda. Antes de las campanas, cada pasada invita a las deudas que todavia
 * no recibieron ningun correo ({@link InvitacionService#invitarPendientes}).
 */
@Service
public class ContactoService {

    private static final Logger log = LoggerFactory.getLogger(ContactoService.class);
    private static final ZoneId CHILE = HorarioDeContacto.CHILE;

    /** La de APOFYX (su documento, seccion 8): para una campana que no trae la suya. */
    static final List<Integer> CADENCIA_POR_OMISION = List.of(1, 4, 11, 25, 45);

    private final CampaignRepository campaigns;
    private final DebtRepository debts;
    private final ContactRepository contactos;
    private final AuthClient auth;
    private final LimiteDeContacto limite;
    private final InvitacionService invitaciones;
    private final ObjectMapper json;
    private final HorarioDeContacto horario;

    public ContactoService(CampaignRepository campaigns, DebtRepository debts, ContactRepository contactos,
                           AuthClient auth, LimiteDeContacto limite, InvitacionService invitaciones, ObjectMapper json,
                           HorarioDeContacto horario) {
        this.campaigns = campaigns;
        this.debts = debts;
        this.contactos = contactos;
        this.auth = auth;
        this.limite = limite;
        this.invitaciones = invitaciones;
        this.json = json;
        this.horario = horario;
    }

    @Scheduled(cron = "${app.contacto.cron:0 */15 * * * *}", zone = "America/Santiago")
    public void pasar() {
        ContactosResponse hecho = contactar(LocalDateTime.now(CHILE), null);
        if (hecho.enviados() + hecho.invitados() > 0) {
            log.info("Campanas: {} toque(s) enviados, {} pendientes; {} invitacion(es) pendientes que salieron",
                    hecho.enviados(), hecho.omitidos(), hecho.invitados());
        }
    }

    /**
     * Una pasada: los toques que tocan a esta hora de Chile. Con otra hora que
     * la de ahora sirve para probar una cadencia sin esperar semanas: los
     * toques quedan fechados a esa hora. Con {@code soloCampana} (su id
     * externo) pasa solo por esa, y las demas no se enteran: tampoco las
     * invitaciones pendientes.
     */
    @Transactional
    public ContactosResponse contactar(LocalDateTime ahora, String soloCampana) {
        if (!horario.permite(ahora)) {
            return new ContactosResponse(0, 0, false, 0);
        }
        Instant cuando = ahora.atZone(CHILE).toInstant();
        LocalDate hoy = ahora.toLocalDate();
        int enviados = 0;
        int omitidos = 0;
        //  A quien ya se le intento en esta pasada y fallo: no se insiste con sus otras deudas.
        Set<Debtor> fallaron = Collections.newSetFromMap(new IdentityHashMap<>());

        int invitados = 0;
        if (soloCampana == null) {
            InvitacionService.Pasada pendientes = invitaciones.invitarPendientes(cuando, fallaron);
            invitados = pendientes.enviadas();
            if (pendientes.cortada()) {
                return new ContactosResponse(0, 0, true, invitados);
            }
        }

        for (Campaign campana : campaigns.findByStatus(Campaign.Status.running)) {
            if ((soloCampana != null && !soloCampana.equals(campana.getExternalId()))
                    || hoy.isBefore(campana.getStartsOn())
                    || (campana.getEndsOn() != null && hoy.isAfter(campana.getEndsOn()))
                    || !porCorreo(campana)) {
                continue;
            }
            List<Integer> cadencia = cadencia(campana, json);
            int tope = Math.min(campana.getAttempts(), cadencia.size());
            //  Las de una empresa suspendida esperan a que la reactiven (#152).
            List<Debt> abiertas = debts.findByCampaign(campana).stream()
                    .filter(d -> d.getStatus() == Debt.Status.open && !d.congelada())
                    .toList();
            if (abiertas.isEmpty()) {
                continue;
            }
            //  Cuantos toques de esta campana lleva cada deuda.
            Map<Long, Long> hechos = contactos.toquesPorDeuda(campana, abiertas).stream()
                    .collect(Collectors.toMap(ToquesHechos::deuda, ToquesHechos::toques));

            for (Debt deuda : abiertas) {
                int toque = hechos.getOrDefault(deuda.getId(), 0L).intValue();
                if (toque >= tope) {
                    continue;
                }
                LocalDate entrada = LocalDate.ofInstant(deuda.getCreatedAt(), CHILE);
                if (entrada.isBefore(campana.getStartsOn())) {
                    entrada = campana.getStartsOn();
                }
                if (hoy.isBefore(entrada.plusDays(cadencia.get(toque)))) {
                    continue;
                }
                Debtor deudor = deuda.getDebtor();
                if (deudor.getEmail() == null || deudor.getEmail().isBlank() || deudor.correoConProblemas()
                        || fallaron.contains(deudor) || !limite.permite(deudor, cuando)) {
                    omitidos++;
                    continue;
                }
                try {
                    auth.emitirCodigo(new AuthClient.PedidoDeCodigo(deudor.getRut(), List.of("correo"),
                            deudor.getEmail(), deuda.getCreditor().getTradeName(), deuda.getExternalId()));
                } catch (CorreoNoSalio noSalio) {
                    deudor.correoNoSalio(noSalio.motivo(), noSalio.deLaDireccion(), cuando);
                    omitidos++;
                    if (!noSalio.deLaDireccion()) {
                        //  El servidor de correo no esta aceptando: con las demas deudas
                        //  seria esperarlo una vez por cada una. Salen en la proxima pasada.
                        log.warn("Campanas: la pasada se corta, el correo no sale: {}", noSalio.motivo());
                        return new ContactosResponse(enviados, omitidos, true, invitados);
                    }
                    fallaron.add(deudor);
                    continue;
                } catch (RuntimeException e) {
                    log.warn("No salio el toque {} de la deuda {}: {}", toque + 1, deuda.getId(), e.getMessage());
                    omitidos++;
                    continue;
                }
                contactos.save(Contact.toque(deuda, campana, toque + 1, AccesoService.enmascarar(deudor.getEmail()))
                        .el(cuando));
                deudor.correoSalio();
                enviados++;
            }
        }
        return new ContactosResponse(enviados, omitidos, true, invitados);
    }

    /** Hoy DataBridge contacta solo por correo: WhatsApp no esta conectado. */
    private static boolean porCorreo(Campaign campana) {
        return campana.getChannels() != null && campana.getChannels().contains("\"correo\"");
    }

    /**
     * Los dias de la cadencia, de menor a mayor. Sin una valida, la de APOFYX.
     * El portal muestra esta misma: la que se cumple, no la que se guardo.
     */
    static List<Integer> cadencia(Campaign campana, ObjectMapper json) {
        if (campana.getCadenceDays() == null || campana.getCadenceDays().isBlank()) {
            return CADENCIA_POR_OMISION;
        }
        try {
            List<Integer> dias = new ArrayList<>();
            for (JsonNode dia : json.readTree(campana.getCadenceDays())) {
                if (dia.canConvertToInt() && dia.asInt() >= 0) {
                    dias.add(dia.asInt());
                }
            }
            return dias.isEmpty() ? CADENCIA_POR_OMISION : dias.stream().sorted().toList();
        } catch (Exception e) {
            return CADENCIA_POR_OMISION;
        }
    }
}
