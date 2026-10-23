package com.tbridge.debt.service;

import com.tbridge.debt.dto.evento.CampanaAvanceDatos;
import com.tbridge.debt.dto.response.AvanceResponse;
import com.tbridge.debt.model.Campaign;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.DebtEvent;
import com.tbridge.debt.repository.CampaignRepository;
import com.tbridge.debt.repository.ContactRepository;
import com.tbridge.debt.repository.DebtEventRepository;
import com.tbridge.debt.repository.DebtRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * El avance de cada campana, una vez al dia (contrato, seccion 8.2).
 *
 * <p>Es lo que la agencia no podia medir por su cuenta: su embudo termina en el
 * mensaje enviado, porque el pago ocurre fuera de su producto (documento de
 * APOFYX, seccion 11.3). Aca ocurre dentro, asi que DataBridge puede decirle
 * cuanto se pago y cuanto se recupero.
 *
 * <p>Los pesos y las UF se informan por separado, como en todo el resto: una
 * suma de ambos no significaria nada.
 */
@Service
public class CampanaAvanceService {

    private static final Logger log = LoggerFactory.getLogger(CampanaAvanceService.class);
    private static final ZoneId CHILE = ZoneId.of("America/Santiago");

    private final CampaignRepository campanas;
    private final DebtRepository debts;
    private final DebtEventRepository eventos;
    private final ContactRepository contactos;
    private final EventosService avisos;

    public CampanaAvanceService(CampaignRepository campanas, DebtRepository debts,
                                DebtEventRepository eventos, ContactRepository contactos, EventosService avisos) {
        this.campanas = campanas;
        this.debts = debts;
        this.eventos = eventos;
        this.contactos = contactos;
        this.avisos = avisos;
    }

    @Scheduled(cron = "${app.eventos.avance-cron:0 0 8 * * *}", zone = "America/Santiago")
    public void deTodasLasCampanas() {
        publicarTodas();
    }

    @Transactional
    public AvanceResponse publicarTodas() {
        List<AvanceResponse.Campana> hechas = new ArrayList<>();
        for (Campaign campana : campanas.findByStatus(Campaign.Status.running)) {
            CampanaAvanceDatos datos = avance(campana);
            int avisados = avisos.publicarDeCampana(campana, datos, Instant.now());
            hechas.add(new AvanceResponse.Campana(datos, avisados));
            log.info("Avance de {}: {} deudas, {} pagos", campana.getExternalId(), datos.deudas(), datos.pagos());
        }
        return new AvanceResponse(hechas);
    }

    /** El acumulado de la campana hasta hoy. */
    public CampanaAvanceDatos avance(Campaign campana) {
        List<Debt> cartera = debts.findByCampaign(campana);
        Map<DebtEvent.Type, Integer> cuantos = new EnumMap<>(DebtEvent.Type.class);
        BigDecimal enPesos = BigDecimal.ZERO;
        BigDecimal enUf = BigDecimal.ZERO;
        //  Lo enviado es todo lo que se le escribio a esos deudores por estas
        //  deudas: la invitacion, los toques, los recordatorios y los reenvios.
        long enviados = 0;

        if (!cartera.isEmpty()) {
            enviados = contactos.countByDebtIn(cartera);
            for (DebtEvent evento : eventos.findByDebtIn(cartera)) {
                cuantos.merge(evento.getType(), 1, Integer::sum);
                if (evento.getType() == DebtEvent.Type.payment_applied && evento.getAmount() != null) {
                    if (evento.getCurrency() == Debt.Currency.UF) {
                        enUf = enUf.add(evento.getAmount());
                    } else {
                        enPesos = enPesos.add(evento.getAmount());
                    }
                }
            }
        }

        return new CampanaAvanceDatos(
                campana.getExternalId(),
                LocalDate.now(CHILE).toString(),
                cartera.size(),
                (int) enviados,
                cuantos.getOrDefault(DebtEvent.Type.portal_entered, 0),
                cuantos.getOrDefault(DebtEvent.Type.repacted, 0),
                cuantos.getOrDefault(DebtEvent.Type.payment_applied, 0),
                cuantos.getOrDefault(DebtEvent.Type.settled, 0),
                cuantos.getOrDefault(DebtEvent.Type.disputed, 0),
                cuantos.getOrDefault(DebtEvent.Type.withdrawn, 0),
                enPesos.longValue(),
                enUf.stripTrailingZeros());
    }
}
