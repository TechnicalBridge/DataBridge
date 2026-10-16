package com.tbridge.debt.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.exception.CarteraInvalida;
import com.tbridge.debt.security.JwtPrincipal;
import com.tbridge.debt.dto.response.CarteraResponse;
import com.tbridge.debt.dto.response.OpcionesCargaResponse;
import com.tbridge.debt.dto.response.OpcionesCargaResponse.AcreedorCarga;
import com.tbridge.debt.dto.response.RevisionResponse;
import com.tbridge.debt.model.Batch;
import com.tbridge.debt.model.Campaign;
import com.tbridge.debt.model.Mandate;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.CampaignRepository;
import com.tbridge.debt.repository.MandateRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * La carga de cartera desde el portal, que es por donde entran los datos a
 * DataBridge: siempre dentro de una campana, con una revision antes de
 * confirmar.
 *
 * <p>El archivo no tiene una ingesta propia. Se convierte a Cartera v1 y entra
 * por {@link CarteraIntakeService}, con sus mismas validaciones. Quien carga
 * es la organizacion de la sesion, nunca un RUT que venga en el formulario.
 */
@Service
@Transactional(readOnly = true)
public class CargaService {

    private static final ZoneId CHILE = ZoneId.of("America/Santiago");
    private static final DateTimeFormatter MARCA = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmssSSS");

    private final CarteraIntakeService ingesta;
    private final DebtService debts;
    private final MandateRepository mandatos;
    private final CampaignRepository campanas;
    private final TransactionTemplate revision;

    public CargaService(CarteraIntakeService ingesta, DebtService debts, MandateRepository mandatos,
                        CampaignRepository campanas, PlatformTransactionManager transacciones) {
        this.ingesta = ingesta;
        this.debts = debts;
        this.mandatos = mandatos;
        this.campanas = campanas;
        this.revision = new TransactionTemplate(transacciones);
    }

    /**
     * Por cuenta de quien puede cargar la empresa de la sesion: la propia, si
     * es acreedora (con las campanas que ella misma gestiona), y la de cada
     * acreedor que le dio un mandato vigente, con sus campanas.
     */
    public OpcionesCargaResponse opciones(JwtPrincipal user) {
        Organization organizacion = empresaDe(user);
        List<AcreedorCarga> acreedores = new ArrayList<>();
        if (organizacion.getKind() != Organization.Kind.agency) {
            acreedores.add(AcreedorCarga.de(organizacion,
                    campanas.findByAgencyAndCreditorOrderByStartsOnDesc(organizacion, organizacion)));
        }
        LocalDate hoy = LocalDate.now();
        for (Mandate mandato : mandatos.findByAgencyAndStatus(organizacion, Mandate.Status.active)) {
            if (mandato.vigenteAl(hoy)) {
                acreedores.add(AcreedorCarga.de(mandato.getCreditor(),
                        campanas.findByAgencyAndCreditorOrderByStartsOnDesc(organizacion, mandato.getCreditor())));
            }
        }
        return new OpcionesCargaResponse(organizacion.getTradeName(), acreedores, ingesta.minDiasMora());
    }

    /** Carga la planilla en la campana. Sin campana no entra nada. */
    @Transactional
    public CarteraResponse subir(JwtPrincipal user, byte[] archivo, String loteId, String fechaCorte,
                                 String acreedorRut, String campana) {
        Carga carga = preparar(user, archivo, loteId, fechaCorte, acreedorRut, campana);
        return ingesta.recibir(carga.organizacion(), carga.cartera(), Batch.Source.file);
    }

    /**
     * Lo que pasaria al cargar la planilla, sin cargarla: la misma ingesta, en
     * una transaccion que se deshace siempre. Por eso dice exactamente lo que
     * va a pasar, con las mismas validaciones, y no deja nada: ni deudas, ni el
     * lote, ni contactos. La invitacion sale despues del commit, asi que
     * tampoco sale ninguna.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public RevisionResponse revisar(JwtPrincipal user, byte[] archivo, String loteId, String fechaCorte,
                                    String acreedorRut, String campana) {
        return revision.execute(estado -> {
            //  Lo primero: pase lo que pase despues, no se guarda nada.
            estado.setRollbackOnly();
            Carga carga = preparar(user, archivo, loteId, fechaCorte, acreedorRut, campana);
            return RevisionResponse.de(ingesta.recibir(carga.organizacion(), carga.cartera(), Batch.Source.file),
                    carga.cartera());
        });
    }

    private record Carga(Organization organizacion, ObjectNode cartera) {
    }

    /**
     * La planilla como Cartera v1, en su campana. El id del lote y la fecha de
     * corte, si no vienen, se arman solos: el corte es hoy.
     */
    private Carga preparar(JwtPrincipal user, byte[] archivo, String loteId, String fechaCorte,
                           String acreedorRut, String campana) {
        Organization organizacion = empresaDe(user);
        if (campana == null || campana.isBlank()) {
            throw new CarteraInvalida("sin_campana",
                    "Toda carga entra en una campaña: elige una o crea una nueva", 400);
        }
        String idCampana = campana.strip();
        campanas.findByAgencyAndExternalId(organizacion, idCampana)
                .filter(c -> c.getStatus() == Campaign.Status.finished)
                .ifPresent(c -> {
                    throw new CarteraInvalida("campana_terminada", "La campaña \"" + c.getName()
                            + "\" está terminada y ya no le escribe a nadie: elige otra o crea una nueva", 409);
                });
        LocalDateTime ahora = LocalDateTime.now(CHILE);
        String lote = loteId == null || loteId.isBlank() ? "CARGA-" + MARCA.format(ahora) : loteId.strip();
        String corte = fechaCorte == null || fechaCorte.isBlank() ? ahora.toLocalDate().toString() : fechaCorte.strip();
        boolean propia = organizacion.getRut().equalsIgnoreCase(acreedorRut);
        CarteraCsv.Lote cabecera = new CarteraCsv.Lote(lote, corte, acreedorRut,
                propia ? null : organizacion.getRut(), idCampana);
        return new Carga(organizacion, CarteraCsv.leer(archivo, cabecera));
    }

    private Organization empresaDe(JwtPrincipal user) {
        if (user == null || !user.isCreditor()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Solo una empresa carga cartera");
        }
        return debts.organizacionDe(user);
    }
}
