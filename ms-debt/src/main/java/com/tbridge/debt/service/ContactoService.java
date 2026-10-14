package com.tbridge.debt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.debt.client.AuthClient;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
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
 *   <li>la ley lo permita: de lunes a sabado, de 8:00 a 20:00, nunca un feriado
 *       ({@link FeriadosDeChile}, mas los decretados), y no mas de lo que deja
 *       {@link LimiteDeContacto}. Un toque que la ley
 *       no deja hoy sale en la proxima pasada en que si.</li>
 * </ul>
 */
@Service
public class ContactoService {

    private static final Logger log = LoggerFactory.getLogger(ContactoService.class);
    private static final ZoneId CHILE = ZoneId.of("America/Santiago");

    /** La de APOFYX (su documento, seccion 8): para una campana que no trae la suya. */
    static final List<Integer> CADENCIA_POR_OMISION = List.of(1, 4, 11, 25, 45);

    private static final LocalTime DESDE = LocalTime.of(8, 0);
    private static final LocalTime HASTA = LocalTime.of(20, 0);

    private final CampaignRepository campaigns;
    private final DebtRepository debts;
    private final ContactRepository contactos;
    private final AuthClient auth;
    private final LimiteDeContacto limite;
    private final ObjectMapper json;
    /** Los que se decretan (elecciones, plebiscitos, feriados especiales): no se pueden calcular. */
    private final Set<LocalDate> decretados;
    /** Los nacionales, calculados una vez por ano. */
    private final Map<Integer, Set<LocalDate>> nacionales = new ConcurrentHashMap<>();

    public ContactoService(CampaignRepository campaigns, DebtRepository debts, ContactRepository contactos,
                           AuthClient auth, LimiteDeContacto limite, ObjectMapper json,
                           @Value("${app.contacto.feriados:}") String decretados) {
        this.campaigns = campaigns;
        this.debts = debts;
        this.contactos = contactos;
        this.auth = auth;
        this.limite = limite;
        this.json = json;
        this.decretados = Arrays.stream(decretados.split(","))
                .map(String::trim)
                .filter(dia -> !dia.isEmpty())
                .map(LocalDate::parse)
                .collect(Collectors.toUnmodifiableSet());
        int ano = LocalDate.now(CHILE).getYear();
        log.info("Feriados de {} en que las campanas no contactan: {}; decretados (CONTACTO_FERIADOS): {}",
                ano, new TreeSet<>(nacionalesDe(ano)), new TreeSet<>(this.decretados));
    }

    @Scheduled(cron = "${app.contacto.cron:0 */15 * * * *}", zone = "America/Santiago")
    public void pasar() {
        ContactosResponse hecho = contactar(LocalDateTime.now(CHILE), null);
        if (hecho.enviados() > 0) {
            log.info("Campanas: {} toque(s) enviados, {} pendientes", hecho.enviados(), hecho.omitidos());
        }
    }

    /** Si a esta hora de Chile se puede hacer cobranza: lunes a sabado, de 8:00 a 20:00, sin feriados. */
    public boolean horaDeContacto(LocalDateTime ahora) {
        LocalTime hora = ahora.toLocalTime();
        return ahora.getDayOfWeek() != DayOfWeek.SUNDAY
                && !esFeriado(ahora.toLocalDate())
                && !hora.isBefore(DESDE) && hora.isBefore(HASTA);
    }

    /** Un feriado nacional de cualquier ano, o uno decretado. */
    boolean esFeriado(LocalDate dia) {
        return decretados.contains(dia) || nacionalesDe(dia.getYear()).contains(dia);
    }

    private Set<LocalDate> nacionalesDe(int ano) {
        return nacionales.computeIfAbsent(ano, FeriadosDeChile::delAno);
    }

    /**
     * Una pasada: los toques que tocan a esta hora de Chile. Con otra hora que
     * la de ahora sirve para probar una cadencia sin esperar semanas: los
     * toques quedan fechados a esa hora. Con {@code soloCampana} (su id
     * externo) pasa solo por esa, y las demas no se enteran.
     */
    @Transactional
    public ContactosResponse contactar(LocalDateTime ahora, String soloCampana) {
        if (!horaDeContacto(ahora)) {
            return new ContactosResponse(0, 0, false);
        }
        Instant cuando = ahora.atZone(CHILE).toInstant();
        LocalDate hoy = ahora.toLocalDate();
        int enviados = 0;
        int omitidos = 0;

        for (Campaign campana : campaigns.findByStatus(Campaign.Status.running)) {
            if ((soloCampana != null && !soloCampana.equals(campana.getExternalId()))
                    || hoy.isBefore(campana.getStartsOn())
                    || (campana.getEndsOn() != null && hoy.isAfter(campana.getEndsOn()))
                    || !porCorreo(campana)) {
                continue;
            }
            List<Integer> cadencia = cadencia(campana, json);
            int tope = Math.min(campana.getAttempts(), cadencia.size());
            List<Debt> abiertas = debts.findByCampaign(campana).stream()
                    .filter(d -> d.getStatus() == Debt.Status.open)
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
                if (deudor.getEmail() == null || deudor.getEmail().isBlank() || !limite.permite(deudor, cuando)) {
                    omitidos++;
                    continue;
                }
                try {
                    auth.emitirCodigo(new AuthClient.PedidoDeCodigo(deudor.getRut(), List.of("correo"),
                            deudor.getEmail(), deuda.getCreditor().getTradeName(), deuda.getExternalId()));
                } catch (RuntimeException e) {
                    log.warn("No salio el toque {} de la deuda {}: {}", toque + 1, deuda.getId(), e.getMessage());
                    omitidos++;
                    continue;
                }
                contactos.save(Contact.toque(deuda, campana, toque + 1, AccesoService.enmascarar(deudor.getEmail()))
                        .el(cuando));
                enviados++;
            }
        }
        return new ContactosResponse(enviados, omitidos, true);
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
