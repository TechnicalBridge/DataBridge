package com.tbridge.debt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.debt.dto.evento.CampanaAvanceDatos;
import com.tbridge.debt.model.Campaign;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.DebtEvent;
import com.tbridge.debt.repository.CampaignRepository;
import com.tbridge.debt.repository.ContactRepository;
import com.tbridge.debt.repository.DebtEventRepository;
import com.tbridge.debt.repository.DebtRepository;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CampanaAvanceTest {

    private final CampaignRepository campanas = mock(CampaignRepository.class);
    private final DebtRepository debts = mock(DebtRepository.class);
    private final DebtEventRepository eventos = mock(DebtEventRepository.class);
    private final ContactRepository contactos = mock(ContactRepository.class);
    private final EventosService avisos = mock(EventosService.class);
    private final CampanaAvanceService servicio = new CampanaAvanceService(campanas, debts, eventos, contactos, avisos);

    private static Debt deuda(Debt.Currency moneda) {
        Debt d = new Debt();
        d.setCurrency(moneda);
        return d;
    }

    private CampanaAvanceDatos avanceCon(List<Debt> cartera, List<DebtEvent> historia) {
        Campaign campana = new Campaign();
        campana.setExternalId("APX-CMP-8");
        when(debts.findByCampaign(campana)).thenReturn(cartera);
        when(eventos.findByDebtIn(anyList())).thenReturn(historia);
        return servicio.avance(campana);
    }

    @Test
    void cuentaElEmbudoDeLaCampana() {
        Debt una = deuda(Debt.Currency.CLP);
        //  Lo enviado se cuenta en la tabla de contactos: la invitacion, dos toques y un reenvio.
        when(contactos.countByDebtIn(List.of(una))).thenReturn(4L);
        CampanaAvanceDatos datos = avanceCon(List.of(una), List.of(
                DebtEvent.de(una, DebtEvent.Type.portal_entered, DebtEvent.Actor.debtor),
                DebtEvent.de(una, DebtEvent.Type.repacted, DebtEvent.Actor.debtor),
                DebtEvent.de(una, DebtEvent.Type.payment_applied, DebtEvent.Actor.system)
                        .conMonto(new BigDecimal("410000"), Debt.Currency.CLP)));

        assertEquals("APX-CMP-8", datos.campanaIdExterno());
        assertEquals(1, datos.deudas());
        assertEquals(4, datos.enviados());
        assertEquals(1, datos.ingresosPortal());
        assertEquals(1, datos.repactaciones());
        assertEquals(1, datos.pagos());
        assertEquals(410000L, datos.recuperadoClp());
    }

    @Test
    void losPesosYLasUfNoSeSuman() {
        Debt enPesos = deuda(Debt.Currency.CLP);
        Debt enUf = deuda(Debt.Currency.UF);
        CampanaAvanceDatos datos = avanceCon(List.of(enPesos, enUf), List.of(
                DebtEvent.de(enPesos, DebtEvent.Type.payment_applied, DebtEvent.Actor.system)
                        .conMonto(new BigDecimal("410000"), Debt.Currency.CLP),
                DebtEvent.de(enUf, DebtEvent.Type.payment_applied, DebtEvent.Actor.system)
                        .conMonto(new BigDecimal("19.25"), Debt.Currency.UF)));

        assertEquals(2, datos.pagos());
        assertEquals(410000L, datos.recuperadoClp());
        assertEquals(new BigDecimal("19.25"), datos.recuperadoUf());
    }

    /**
     * Lo que DataBridge no mide no viaja en cero: un cero diria "ninguno" y lo
     * que corresponde decir es "no lo se".
     */
    @Test
    void loQueNoSeMideNoViaja() {
        CampanaAvanceDatos datos = avanceCon(List.of(), List.of());
        JsonNode comoViaja = new ObjectMapper().valueToTree(datos);
        for (String noMedido : List.of("entregados", "abiertos", "respuestas", "bajas", "reportes_fraude")) {
            assertFalse(comoViaja.has(noMedido), noMedido);
        }
        assertEquals(0, comoViaja.get("deudas").asInt());
        assertEquals(0, comoViaja.get("enviados").asInt(), "sin cartera no hay a quien haberle escrito");
        assertEquals("APX-CMP-8", comoViaja.get("campana_id_externo").asText());
    }
}
