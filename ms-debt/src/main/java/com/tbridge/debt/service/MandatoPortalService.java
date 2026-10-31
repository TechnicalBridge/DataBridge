package com.tbridge.debt.service;

import com.tbridge.debt.dto.request.MandatoPortalRequest;
import com.tbridge.debt.dto.request.RenovarMandatoRequest;
import com.tbridge.debt.dto.response.MandatoPortalResponse;
import com.tbridge.debt.dto.response.MandatosResponse;
import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.Mandate;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.DebtRepository;
import com.tbridge.debt.repository.MandateRepository;
import com.tbridge.debt.repository.OrganizationRepository;
import com.tbridge.debt.util.Rut;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * Los mandatos desde el portal de la agencia (#234). Con la API apagada (#191),
 * es por donde nace un mandato: la agencia lo declara, lo renueva y lo termina.
 *
 * <p><b>La agencia responde por el mandato</b>, igual que con la API: DataBridge
 * guarda que persona lo declaro, renovo o termino, y cuando. Un acreedor que no
 * conoce se registra con los nombres que escribe la agencia
 * ({@link MandatoService#acreedorDelMandato}).
 *
 * <p>Terminado o vencido, <b>lo ya cargado se sigue cobrando</b> hasta pagarse:
 * solo deja de poder cargarse cartera y crearse campanas para ese acreedor, como
 * decidio el PO.
 */
@Service
public class MandatoPortalService {

    /** Las que siguen en gestion: las que se cobran aunque el mandato termine. */
    private static final List<Debt.Status> EN_GESTION =
            List.of(Debt.Status.open, Debt.Status.repacted, Debt.Status.disputed);
    private static final int MORA_MAXIMA_POR_OMISION = 120;
    private static final int MORA_MAXIMA_TOPE = 3650;

    private final MandateRepository mandates;
    private final OrganizationRepository organizations;
    private final DebtRepository debts;
    private final MandatoService mandatos;
    private final int minDiasMora;

    public MandatoPortalService(MandateRepository mandates, OrganizationRepository organizations,
                                DebtRepository debts, MandatoService mandatos,
                                @Value("${app.cartera.min-dias-mora:30}") int minDiasMora) {
        this.mandates = mandates;
        this.organizations = organizations;
        this.debts = debts;
        this.mandatos = mandatos;
        this.minDiasMora = minDiasMora;
    }

    /** Los de la agencia, el mas reciente primero. Una empresa que cobra lo suyo no tiene. */
    @Transactional(readOnly = true)
    public MandatosResponse listar(Organization empresa) {
        if (!empresa.esAgencia()) {
            return new MandatosResponse(false, List.of());
        }
        LocalDate hoy = LocalDate.now();
        return new MandatosResponse(true, mandates.deLaAgencia(empresa).stream().map(m -> armar(m, hoy)).toList());
    }

    /**
     * La agencia declara que cobra por cuenta de un acreedor. No puede tener dos
     * con el mismo acreedor a la vez: el que tiene se renueva o se termina.
     */
    @Transactional
    public MandatoPortalResponse declarar(Organization agencia, String quien, MandatoPortalRequest pedido) {
        exigirAgencia(agencia);
        String rut = Rut.normalizar(pedido.acreedorRut());
        if (!Rut.esValido(rut)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "El RUT del acreedor no es válido");
        }
        if (rut.equals(agencia.getRut())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Una agencia no puede tener un mandato sobre sí misma");
        }
        LocalDate hoy = LocalDate.now();
        LocalDate desde = fecha(pedido.desde(), hoy, "La fecha de inicio no es válida");
        LocalDate hasta = fecha(pedido.hasta(), null, "La fecha de término no es válida");
        validarTermino(desde, hasta, hoy);
        int moraMaxima = pedido.moraMaximaDias() == null ? MORA_MAXIMA_POR_OMISION : pedido.moraMaximaDias();
        if (moraMaxima < minDiasMora || moraMaxima > MORA_MAXIMA_TOPE) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "La mora máxima va de " + minDiasMora + " a "
                    + MORA_MAXIMA_TOPE + " días: con menos de " + minDiasMora + " no entra ninguna deuda");
        }
        BigDecimal descuento = pedido.descuentoMaximoMora();
        if (descuento != null && (descuento.signum() < 0 || descuento.compareTo(BigDecimal.valueOf(100)) > 0
                || descuento.stripTrailingZeros().scale() > 2)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "El descuento máximo es un % de 0 a 100");
        }
        if (organizations.findByRut(rut).isEmpty() && vacio(pedido.razonSocial())) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "DataBridge todavía no conoce a ese acreedor: escribe su razón social");
        }

        Organization acreedor = mandatos.acreedorDelMandato(rut, pedido.razonSocial(), pedido.nombre());
        List<Mandate> conElAcreedor = mandates.deLaAgencia(agencia).stream()
                .filter(m -> m.getCreditor().getId().equals(acreedor.getId()))
                .toList();
        if (conElAcreedor.stream().anyMatch(m -> m.getStatus() == Mandate.Status.active
                && !"vencido".equals(m.estadoAl(hoy)))) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Ya tienes un mandato con " + acreedor.getTradeName() + ": renuévalo o termínalo");
        }
        if (conElAcreedor.stream().anyMatch(m -> m.getValidFrom().equals(desde))) {
            throw new ApiException(HttpStatus.CONFLICT, "Ya hubo un mandato con " + acreedor.getTradeName()
                    + " que empezaba ese día: elige otra fecha de inicio");
        }

        Mandate mandato = new Mandate();
        mandato.setAgency(agencia);
        mandato.setCreditor(acreedor);
        mandato.setValidFrom(desde);
        mandato.setValidTo(hasta);
        mandato.setMaxOverdueDays((short) moraMaxima);
        mandato.setMaxMoraDiscount(descuento);
        mandato.setDeclaredBy(quienFue(quien));
        mandates.save(mandato);
        return armar(mandato, hoy);
    }

    /** Le cambia la fecha de termino: una de hoy en adelante, o ninguna. Uno vencido vuelve a estar vigente. */
    @Transactional
    public MandatoPortalResponse renovar(Organization agencia, String quien, Long id, RenovarMandatoRequest pedido) {
        Mandate mandato = suyo(agencia, id);
        if (mandato.getStatus() == Mandate.Status.ended) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Ese mandato ya terminó: si vuelven a trabajar juntos, declara uno nuevo");
        }
        LocalDate hoy = LocalDate.now();
        LocalDate hasta = fecha(pedido == null ? null : pedido.hasta(), null, "La fecha de término no es válida");
        validarTermino(mandato.getValidFrom(), hasta, hoy);
        mandato.setValidTo(hasta);
        mandato.setRenewedAt(Instant.now());
        mandato.setRenewedBy(quienFue(quien));
        mandates.save(mandato);
        return armar(mandato, hoy);
    }

    /** Lo termina desde ya. Lo que se cargo con el se sigue cobrando hasta pagarse. */
    @Transactional
    public MandatoPortalResponse terminar(Organization agencia, String quien, Long id) {
        Mandate mandato = suyo(agencia, id);
        if (mandato.getStatus() == Mandate.Status.ended) {
            throw new ApiException(HttpStatus.CONFLICT, "Ese mandato ya estaba terminado");
        }
        mandato.setStatus(Mandate.Status.ended);
        mandato.setEndedAt(Instant.now());
        mandato.setEndedBy(quienFue(quien));
        mandates.save(mandato);
        return armar(mandato, LocalDate.now());
    }

    private Mandate suyo(Organization agencia, Long id) {
        exigirAgencia(agencia);
        return mandates.findByIdAndAgency(id, agencia)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Ese mandato no es de tu empresa"));
    }

    private MandatoPortalResponse armar(Mandate mandato, LocalDate hoy) {
        return MandatoPortalResponse.de(mandato, hoy, debts.countByMandateAndStatusIn(mandato, EN_GESTION));
    }

    private static void exigirAgencia(Organization empresa) {
        if (!empresa.esAgencia()) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "Los mandatos son de las agencias de cobranza: tu empresa cobra lo suyo directo");
        }
    }

    private static void validarTermino(LocalDate desde, LocalDate hasta, LocalDate hoy) {
        if (hasta == null) {
            return;
        }
        //  Primero lo que ya paso: sin desde, el inicio es hoy, y decir "antes del inicio" confunde.
        if (hasta.isBefore(hoy)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Esa fecha de término ya pasó: pon hoy o una posterior");
        }
        if (hasta.isBefore(desde)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "El término no puede ser antes del inicio");
        }
    }

    /** Una fecha como 2026-10-01; vacia, la de por omision. Una que no se entiende no se adivina. */
    private static LocalDate fecha(String texto, LocalDate porOmision, String error) {
        if (vacio(texto)) {
            return porOmision;
        }
        try {
            return LocalDate.parse(texto.trim());
        } catch (DateTimeParseException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, error);
        }
    }

    private static boolean vacio(String texto) {
        return texto == null || texto.isBlank();
    }

    private static String quienFue(String quien) {
        return quien == null ? null : quien.length() > 160 ? quien.substring(0, 160) : quien;
    }
}
