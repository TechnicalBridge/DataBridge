package com.tbridge.debt.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.debt.dto.evento.CampanaAvanceDatos;
import com.tbridge.debt.dto.request.CampanaRequest;
import com.tbridge.debt.dto.response.CampanaPortalResponse;
import com.tbridge.debt.exception.CarteraInvalida;
import com.tbridge.debt.model.Campaign;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.CampaignRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Las campanas desde el portal de empresas: la lista con su avance, crearlas o
 * cambiarlas, y pausarlas, reanudarlas o terminarlas.
 *
 * <p>No tiene reglas propias: guarda con {@link MandatoService#registrarCampana},
 * las mismas que el contrato de integracion. Una empresa ve y cambia las
 * campanas que gestiona: las de los acreedores que le dieron mandato y, si
 * cobra sin agencia, las suyas.
 */
@Service
public class CampanaService {

    private static final ZoneId CHILE = ZoneId.of("America/Santiago");
    private static final SecureRandom AZAR = new SecureRandom();
    private static final String LETRAS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final CampaignRepository campanas;
    private final MandatoService mandatos;
    private final CampanaAvanceService avance;
    private final ObjectMapper json;

    public CampanaService(CampaignRepository campanas, MandatoService mandatos, CampanaAvanceService avance,
                          ObjectMapper json) {
        this.campanas = campanas;
        this.mandatos = mandatos;
        this.avance = avance;
        this.json = json;
    }

    @Transactional(readOnly = true)
    public List<CampanaPortalResponse> listar(Organization empresa) {
        //  Primero las que se manejan (en curso, pausadas) y al final las terminadas;
        //  dentro de cada estado, la mas nueva primero.
        return campanas.findByAgencyOrderByStartsOnDesc(empresa).stream()
                .sorted(Comparator.comparingInt(c -> c.getStatus().ordinal()))
                .map(this::respuesta).toList();
    }

    /** Crea o cambia una campana. Sin id externo, DataBridge le pone uno. */
    @Transactional
    public CampanaPortalResponse guardar(Organization empresa, CampanaRequest pedido) {
        CampanaRequest conId = pedido.idExterno() == null || pedido.idExterno().isBlank()
                ? pedido.conIdExterno(nuevoId())
                : pedido;
        mandatos.registrarCampana(empresa, conId);
        return respuesta(buscar(empresa, conId.idExterno()));
    }

    /** Pausar, reanudar o terminar: solo el estado, el resto queda como estaba. */
    @Transactional
    public CampanaPortalResponse cambiarEstado(Organization empresa, String idExterno, String estado) {
        Campaign campana = buscar(empresa, idExterno);
        mandatos.registrarCampana(empresa, new CampanaRequest(idExterno, campana.getCreditor().getRut(),
                null, null, null, null, null, null, estado));
        return respuesta(campana);
    }

    private Campaign buscar(Organization empresa, String idExterno) {
        return campanas.findByAgencyAndExternalId(empresa, idExterno)
                .orElseThrow(() -> new CarteraInvalida("campana_desconocida", "Esa campaña no es de tu empresa", 404));
    }

    /** CMP-20261008-K7Q2: el dia y cuatro letras al azar, sin las que se confunden (0, O, 1, I). */
    private static String nuevoId() {
        StringBuilder id = new StringBuilder("CMP-")
                .append(LocalDate.now(CHILE).format(DateTimeFormatter.BASIC_ISO_DATE)).append('-');
        for (int i = 0; i < 4; i++) {
            id.append(LETRAS.charAt(AZAR.nextInt(LETRAS.length())));
        }
        return id.toString();
    }

    private CampanaPortalResponse respuesta(Campaign campana) {
        CampanaAvanceDatos datos = avance.avance(campana);
        return new CampanaPortalResponse(
                campana.getExternalId(),
                campana.getName(),
                campana.getCreditor().getRut(),
                campana.getCreditor().getTradeName(),
                campana.getAgency().getId().equals(campana.getCreditor().getId()),
                campana.getStartsOn(),
                campana.getEndsOn(),
                lista(campana.getChannels(), new TypeReference<List<String>>() { }),
                campana.getAttempts() == null ? 0 : campana.getAttempts(),
                ContactoService.cadencia(campana, json),
                switch (campana.getStatus()) {
                    case running -> "en_curso";
                    case paused -> "pausada";
                    case finished -> "terminada";
                },
                datos.deudas(), datos.enviados(), datos.pagos(), datos.saldadas(),
                porTramo(campana.getMoraDiscount()));
    }

    /** El descuento por tramo, como lo guardo {@link DescuentoService#normalizar}. Null sin descuento. */
    private Map<String, BigDecimal> porTramo(String texto) {
        if (texto == null || texto.isBlank()) {
            return null;
        }
        try {
            return json.readValue(texto, new TypeReference<Map<String, BigDecimal>>() { });
        } catch (Exception e) {
            return null;
        }
    }

    private <T> List<T> lista(String texto, TypeReference<List<T>> tipo) {
        if (texto == null || texto.isBlank()) {
            return List.of();
        }
        try {
            return json.readValue(texto, tipo);
        } catch (Exception e) {
            return List.of();
        }
    }
}
