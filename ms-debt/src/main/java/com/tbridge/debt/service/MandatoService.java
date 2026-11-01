package com.tbridge.debt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.tbridge.debt.util.Rut;
import com.tbridge.debt.dto.request.CampanaRequest;
import com.tbridge.debt.dto.request.MandatoRequest;
import com.tbridge.debt.dto.response.CampanaResponse;
import com.tbridge.debt.dto.response.MandatoResponse;
import com.tbridge.debt.exception.CarteraInvalida;
import com.tbridge.debt.model.Campaign;
import com.tbridge.debt.model.Mandate;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.CampaignRepository;
import com.tbridge.debt.repository.MandateRepository;
import com.tbridge.debt.repository.OrganizationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

/**
 * Contrato 2: el mandato y la campana.
 *
 * <p>Lo que la agencia le dice a DataBridge antes de pasarle una cartera. No
 * lleva ningun dato personal: es quien cobra por cuenta de quien, y con que
 * estrategia.
 */
@Service
public class MandatoService {

    private final OrganizationRepository organizations;
    private final MandateRepository mandates;
    private final CampaignRepository campaigns;
    private final DescuentoService descuentos;

    public MandatoService(OrganizationRepository organizations, MandateRepository mandates,
                          CampaignRepository campaigns, DescuentoService descuentos) {
        this.organizations = organizations;
        this.mandates = mandates;
        this.campaigns = campaigns;
        this.descuentos = descuentos;
    }

    /**
     * La agencia declara que cobra por cuenta de un acreedor.
     *
     * <p><b>La agencia responde por el mandato:</b> es su contrato con el
     * acreedor. DataBridge registra quien lo declaro y cuando, que es lo que
     * permite reconstruir despues con que autorizacion se cobro.
     *
     * <p>Por lo mismo, <b>un acreedor que DataBridge no conoce se registra aca</b>,
     * con los nombres que manda la agencia. Asi una empresa nueva de la agencia
     * entra sin que nadie la cargue a mano en DataBridge.
     */
    @Transactional
    public MandatoResponse registrarMandato(Organization agencia, MandatoRequest pedido) {
        if (!agencia.esAgencia()) {
            throw new CarteraInvalida("no_es_agencia",
                    "Esa organizacion no esta registrada como agencia de cobranza", 403);
        }
        Organization acreedor = buscarORegistrarAcreedor(pedido);
        if (acreedor.getId().equals(agencia.getId())) {
            throw new CarteraInvalida("mandato_invalido", "Una agencia no puede tener mandato sobre si misma");
        }

        LocalDate desde = fecha(pedido.vigenteDesde(), LocalDate.now());
        BigDecimal descuento = DescuentoService.porcentajeValido(pedido.descuentoMaximoMora());

        //  Declarar dos veces el mismo mandato no es un error: es un reintento.
        //  Se devuelve el que ya existe en vez de chocar contra el indice unico.
        Mandate existente = mandates.findByAgencyAndCreditorAndStatus(agencia, acreedor, Mandate.Status.active)
                .stream()
                .filter(m -> m.getValidFrom().equals(desde))
                .findFirst()
                .orElse(null);
        if (existente != null) {
            //  Reenviar el mandato es como el acreedor cambia su maximo: si lo baja,
            //  sus campanas quedan recortadas desde ya.
            existente.setMaxMoraDiscount(descuento);
            mandates.save(existente);
            return MandatoResponse.from(existente);
        }

        Mandate mandato = new Mandate();
        mandato.setAgency(agencia);
        mandato.setCreditor(acreedor);
        mandato.setValidFrom(desde);
        if (pedido.vigenteHasta() != null) {
            mandato.setValidTo(fecha(pedido.vigenteHasta(), null));
        }
        if (pedido.moraMaximaDias() != null) {
            mandato.setMaxOverdueDays(pedido.moraMaximaDias().shortValue());
        }
        mandato.setMaxMoraDiscount(descuento);
        mandato.setDeclaredBy(agencia.getTradeName());
        mandates.save(mandato);
        return MandatoResponse.from(mandato);
    }

    /**
     * Registra o actualiza la campana de quien cobra: una agencia con mandato
     * vigente sobre el acreedor, o el mismo acreedor cuando cobra sin agencia.
     * En ese caso la campana queda a su nombre como quien la gestiona.
     */
    @Transactional
    public CampanaResponse registrarCampana(Organization quien, CampanaRequest pedido) {
        Organization acreedor = buscarAcreedor(pedido.acreedorRut());
        String idExterno = pedido.idExterno();
        if (idExterno == null || idExterno.isBlank()) {
            throw new CarteraInvalida("campana_incompleta", "La campana necesita id_externo");
        }
        if (idExterno.length() > 64) {
            throw new CarteraInvalida("id_invalido", "El id_externo de la campana tiene hasta 64 caracteres");
        }
        boolean esElAcreedor = quien.getId().equals(acreedor.getId());
        Optional<Campaign> guardada = campaigns.findByAgencyAndExternalId(quien, idExterno);
        boolean nueva = guardada.isEmpty();
        //  Una campana nueva necesita un mandato vigente hoy. Una que ya existe, del
        //  mismo acreedor, se puede cambiar, pausar o terminar aunque su mandato haya
        //  terminado o vencido: lo ya cargado se sigue cobrando (#234).
        boolean yaEraSuya = !nueva && guardada.get().getCreditor() != null
                && guardada.get().getCreditor().getId().equals(acreedor.getId());
        if (!esElAcreedor && !yaEraSuya && !mandatoVigente(quien, acreedor)) {
            throw new CarteraInvalida("sin_mandato", "No hay mandato vigente sobre ese acreedor", 403);
        }

        Campaign campana = guardada.orElseGet(Campaign::new);
        campana.setAgency(quien);
        campana.setCreditor(acreedor);
        campana.setExternalId(idExterno);
        //  Lo que no viene no cambia: a una campana que ya existe, mandarle solo
        //  el estado no le pisa el nombre con su id ni el inicio con hoy.
        if (pedido.nombre() != null && !pedido.nombre().isBlank()) {
            if (pedido.nombre().strip().length() > 120) {
                throw new CarteraInvalida("nombre_invalido", "El nombre de la campana tiene hasta 120 caracteres");
            }
            campana.setName(pedido.nombre().strip());
        } else if (nueva) {
            campana.setName(idExterno);
        }
        if (pedido.inicio() != null) {
            campana.setStartsOn(fecha(pedido.inicio(), LocalDate.now()));
        } else if (nueva) {
            campana.setStartsOn(LocalDate.now());
        }
        if (pedido.fin() != null) {
            campana.setEndsOn(fecha(pedido.fin(), null));
        }
        if (campana.getEndsOn() != null && campana.getEndsOn().isBefore(campana.getStartsOn())) {
            throw new CarteraInvalida("fechas_invalidas", "La campana no puede terminar antes de empezar");
        }
        if (presente(pedido.canales())) {
            campana.setChannels(pedido.canales().toString());
        }
        if (pedido.intentos() != null) {
            if (pedido.intentos() < 1 || pedido.intentos() > 10) {
                throw new CarteraInvalida("intentos_invalidos", "Los intentos de una campana van de 1 a 10");
            }
            campana.setAttempts(pedido.intentos().shortValue());
        }
        if (presente(pedido.cadenciaDias())) {
            validarCadencia(pedido.cadenciaDias());
            campana.setCadenceDays(pedido.cadenciaDias().toString());
        }
        if (presente(pedido.descuentoMoraPorTramo())) {
            campana.setMoraDiscount(descuentos.normalizar(pedido.descuentoMoraPorTramo(),
                    descuentos.tope(quien, acreedor, LocalDate.now())));
        }
        if (pedido.estado() != null && !pedido.estado().isBlank()) {
            Campaign.Status nuevo = switch (pedido.estado().trim()) {
                case "en_curso" -> Campaign.Status.running;
                case "pausada" -> Campaign.Status.paused;
                case "terminada" -> Campaign.Status.finished;
                default -> throw new CarteraInvalida("estado_invalido",
                        "El estado de la campana va como en_curso, pausada o terminada");
            };
            if (!nueva && campana.getStatus() == Campaign.Status.finished && nuevo != Campaign.Status.finished) {
                throw new CarteraInvalida("campana_terminada",
                        "Una campana terminada no se puede volver a poner en curso ni pausar", 409);
            }
            campana.setStatus(nuevo);
        }
        campaigns.save(campana);
        return CampanaResponse.from(campana);
    }

    private boolean mandatoVigente(Organization agencia, Organization acreedor) {
        LocalDate hoy = LocalDate.now();
        return mandates.findByAgencyAndCreditorAndStatus(agencia, acreedor, Mandate.Status.active).stream()
                .anyMatch(m -> m.vigenteAl(hoy));
    }

    /** Dias enteros, mayores que cero y crecientes: el dia de cada toque, contado desde la entrada. */
    private static void validarCadencia(JsonNode cadencia) {
        int anterior = 0;
        for (JsonNode dia : cadencia) {
            if (!dia.canConvertToInt() || !dia.isIntegralNumber() || dia.asInt() <= anterior) {
                throw new CarteraInvalida("cadencia_invalida",
                        "La cadencia son dias crecientes y mayores que cero, por ejemplo 1, 4, 11");
            }
            anterior = dia.asInt();
        }
    }

    private Organization buscarAcreedor(String crudo) {
        String rut = rutDelAcreedor(crudo);
        return organizations.findByRut(rut).orElseThrow(() -> new CarteraInvalida(
                "acreedor_desconocido", "El acreedor " + rut + " no esta registrado", 404));
    }

    /**
     * El acreedor del mandato, registrado si es nuevo. Uno que ya existe no se
     * toca: sus nombres los fijo quien lo registro primero, y un mandato de otra
     * agencia no los puede cambiar.
     */
    private Organization buscarORegistrarAcreedor(MandatoRequest pedido) {
        return acreedorDelMandato(rutDelAcreedor(pedido.acreedorRut()), pedido.razonSocial(), pedido.nombreFantasia());
    }

    /**
     * El acreedor de un mandato, con su RUT ya normalizado y valido. Si
     * DataBridge no lo conoce, lo registra con esos nombres. Tambien lo usa el
     * mandato que se declara en el portal (#234).
     */
    Organization acreedorDelMandato(String rut, String razonSocial, String nombre) {
        return organizations.findByRut(rut).orElseGet(() -> {
            String legal = texto(razonSocial, rut, 160);
            Organization nueva = new Organization();
            nueva.setRut(rut);
            nueva.setLegalName(legal);
            nueva.setTradeName(texto(nombre, legal, 120));
            nueva.setKind(Organization.Kind.creditor);
            return organizations.save(nueva);
        });
    }

    private static String rutDelAcreedor(String crudo) {
        String rut = Rut.normalizar(crudo);
        if (!Rut.esValido(rut)) {
            throw new CarteraInvalida("acreedor_invalido", "El RUT del acreedor no es valido");
        }
        return rut;
    }

    /** El texto recortado a lo que cabe en la columna, o el valor por omision si viene vacio. */
    private static String texto(String valor, String porOmision, int largo) {
        String limpio = valor == null || valor.isBlank() ? porOmision : valor.strip();
        return limpio.length() > largo ? limpio.substring(0, largo) : limpio;
    }

    private static boolean presente(JsonNode nodo) {
        return nodo != null && !nodo.isNull() && !nodo.isMissingNode();
    }

    /** Una fecha que no se entiende toma el valor por omision: asi lo define la version 1 del contrato. */
    private static LocalDate fecha(String texto, LocalDate porDefecto) {
        try {
            return LocalDate.parse(texto);
        } catch (Exception e) {
            return porDefecto;
        }
    }
}
