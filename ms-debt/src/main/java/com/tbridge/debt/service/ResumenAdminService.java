package com.tbridge.debt.service;

import com.tbridge.debt.dto.response.ComisionesResponse;
import com.tbridge.debt.dto.response.CampanaAdminResponse;
import com.tbridge.debt.dto.response.ResumenAdminResponse;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.DebtRepository;
import com.tbridge.debt.repository.CampaignRepository;
import com.tbridge.debt.repository.OrganizationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

@Service
@Transactional(readOnly = true)
public class ResumenAdminService {
    private final OrganizationRepository empresas;
    private final DebtRepository deudas;
    private final ComisionService comisiones;
    private final CampaignRepository campanas;

    public ResumenAdminService(OrganizationRepository empresas, DebtRepository deudas, ComisionService comisiones,
                               CampaignRepository campanas) {
        this.empresas = empresas;
        this.deudas = deudas;
        this.comisiones = comisiones;
        this.campanas = campanas;
    }

    public List<CampanaAdminResponse> campanas() {
        return campanas.contarParaAdmin().stream()
                .map(c -> new CampanaAdminResponse(c.id(), c.nombre(), c.empresa(), c.estado().name(), c.deudas()))
                .toList();
    }

    public ResumenAdminResponse resumen() {
        Map<String, Long> estados = new LinkedHashMap<>();
        for (Debt.Status estado : Debt.Status.values()) {
            estados.put(estado.name(), 0L);
        }
        deudas.contarPorEstado().forEach(fila -> estados.put(fila.estado().name(), fila.deudas()));
        ComisionesResponse mes = comisiones.deTodas(YearMonth.now(ZoneId.of("America/Santiago")));
        return new ResumenAdminResponse(empresas.countByStatus(Organization.Status.active),
                empresas.countByStatus(Organization.Status.suspended), estados, mes.mes(),
                mes.recuperado(), mes.comision());
    }
}
