package com.tbridge.debt.service;

import com.tbridge.debt.dto.response.ComisionesResponse;
import com.tbridge.debt.dto.response.CampanaAdminResponse;
import com.tbridge.debt.dto.response.ResumenAdminResponse;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.DebtRepository;
import com.tbridge.debt.repository.CampaignRepository;
import com.tbridge.debt.repository.ContactRepository;
import com.tbridge.debt.repository.ContactosPorCampana;
import com.tbridge.debt.repository.OrganizationRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class ResumenAdminService {
    private final OrganizationRepository empresas;
    private final DebtRepository deudas;
    private final ComisionService comisiones;
    private static final ZoneId CHILE = ZoneId.of("America/Santiago");

    private final CampaignRepository campanas;
    private final ContactRepository contactos;
    private final Clock reloj;

    @Autowired
    public ResumenAdminService(OrganizationRepository empresas, DebtRepository deudas, ComisionService comisiones,
                               CampaignRepository campanas, ContactRepository contactos) {
        this(empresas, deudas, comisiones, campanas, contactos, Clock.systemUTC());
    }

    /** Para las pruebas, que necesitan fijar el dia. */
    ResumenAdminService(OrganizationRepository empresas, DebtRepository deudas, ComisionService comisiones,
                        CampaignRepository campanas, ContactRepository contactos, Clock reloj) {
        this.empresas = empresas;
        this.deudas = deudas;
        this.comisiones = comisiones;
        this.campanas = campanas;
        this.contactos = contactos;
        this.reloj = reloj;
    }

    /**
     * Las campanas de todas las empresas, con sus contactos de hoy y de los
     * ultimos siete dias en Chile, la misma semana que cuenta el limite de la
     * ley (#441). Dos consultas para todas, no una por campana.
     */
    public List<CampanaAdminResponse> campanas() {
        LocalDate hoy = LocalDate.now(reloj.withZone(CHILE));
        Instant desdeHoy = hoy.atStartOfDay(CHILE).toInstant();
        Instant desdeLaSemana = hoy.minusDays(6).atStartOfDay(CHILE).toInstant();
        Map<Long, ContactosPorCampana> contactados = contactos.porCampanaDesde(desdeLaSemana, desdeHoy).stream()
                .collect(Collectors.toMap(ContactosPorCampana::campana, Function.identity()));
        return campanas.contarParaAdmin().stream()
                .map(c -> {
                    ContactosPorCampana enviados = contactados.get(c.id());
                    return new CampanaAdminResponse(c.id(), c.nombre(), c.empresa(), c.acreedor(), c.estado().name(),
                            c.desde(), c.hasta(), c.deudas(), c.abiertas(), enviados == null ? 0 : enviados.hoy(),
                            enviados == null ? 0 : enviados.semana());
                })
                .toList();
    }

    public ResumenAdminResponse resumen() {
        Map<String, Long> estados = new LinkedHashMap<>();
        for (Debt.Status estado : Debt.Status.values()) {
            estados.put(estado.name(), 0L);
        }
        deudas.contarPorEstado().forEach(fila -> estados.put(fila.estado().name(), fila.deudas()));
        ComisionesResponse mes = comisiones.deTodas(YearMonth.now(CHILE));
        return new ResumenAdminResponse(empresas.countByStatus(Organization.Status.active),
                empresas.countByStatus(Organization.Status.suspended), estados, mes.mes(),
                mes.recuperado(), mes.comision());
    }
}
