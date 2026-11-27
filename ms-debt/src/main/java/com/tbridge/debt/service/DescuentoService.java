package com.tbridge.debt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tbridge.debt.exception.CarteraInvalida;
import com.tbridge.debt.model.Campaign;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.Mandate;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.MandateRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * El descuento por pronto pago (contrato §7.1): cuanto de los intereses de
 * mora se le condona a quien paga toda su deuda durante la campana.
 *
 * <p>Lo autoriza el acreedor con un maximo en el mandato, y la campana lo
 * ofrece por tramo de mora sin pasarlo. El maximo se aplica al cobrar, no al
 * guardar la campana: si el acreedor lo baja, sus campanas quedan recortadas
 * desde ese momento, sin volver a registrarlas.
 */
@Service
public class DescuentoService {

    /** Los tramos del contrato, por los dias del cargo impago mas antiguo. */
    public static final List<String> TRAMOS = List.of("1-30", "31-90", "91-120");

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    private final MandateRepository mandates;
    private final ObjectMapper json;

    public DescuentoService(MandateRepository mandates, ObjectMapper json) {
        this.mandates = mandates;
        this.json = json;
    }

    /** El tramo de una deuda con estos dias de mora. Pasados los 120, el ultimo: la mas antigua no recibe menos. */
    public static String tramoDe(long diasDeMora) {
        if (diasDeMora <= 30) {
            return "1-30";
        }
        return diasDeMora <= 90 ? "31-90" : "91-120";
    }

    /**
     * El % que se le condona hoy a una deuda que se paga entera: el de su tramo
     * en la campana, recortado al maximo vigente. Cero si no tiene campana, si
     * la campana no esta en curso o hoy no esta entre sus fechas, o si no
     * ofrece descuento.
     */
    public BigDecimal porcentaje(Debt deuda, long diasDeMora, LocalDate hoy) {
        return porcentaje(deuda, diasDeMora, hoy,
                () -> tope(deuda.getCampaign().getAgency(), deuda.getCampaign().getCreditor(), hoy));
    }

    BigDecimal porcentaje(Debt deuda, long diasDeMora, LocalDate hoy, Supplier<BigDecimal> tope) {
        Campaign campana = deuda.getCampaign();
        if (campana == null || campana.getMoraDiscount() == null || diasDeMora <= 0
                || campana.getStatus() != Campaign.Status.running
                || hoy.isBefore(campana.getStartsOn())
                || (campana.getEndsOn() != null && hoy.isAfter(campana.getEndsOn()))) {
            return BigDecimal.ZERO;
        }
        BigDecimal delTramo = porTramo(campana).getOrDefault(tramoDe(diasDeMora), BigDecimal.ZERO);
        return delTramo.min(tope.get());
    }

    record Empresas(Long agencia, Long acreedor) {
        static Empresas de(Campaign campana) {
            return new Empresas(campana.getAgency().getId(), campana.getCreditor().getId());
        }
    }

    Map<Empresas, BigDecimal> topesParaListado(List<Debt> deudas, LocalDate hoy) {
        List<Long> ids = deudas.stream().filter(deuda -> {
            Campaign campana = deuda.getCampaign();
            return campana != null && campana.getMoraDiscount() != null
                    && !campana.getAgency().getId().equals(campana.getCreditor().getId());
        }).map(Debt::getId).toList();
        if (ids.isEmpty()) return Map.of();

        Map<Empresas, Mandate> vigentes = new HashMap<>();
        mandates.paraCampanasDeDeudas(ids, Mandate.Status.active).stream().filter(m -> m.vigenteAl(hoy))
                .forEach(m -> vigentes.merge(new Empresas(m.getAgency().getId(), m.getCreditor().getId()),
                        m, (a, b) -> b.getValidFrom().isAfter(a.getValidFrom()) ? b : a));
        Map<Empresas, BigDecimal> topes = new HashMap<>();
        vigentes.forEach((par, mandato) -> {
            if (mandato.getMaxMoraDiscount() != null) topes.put(par, mandato.getMaxMoraDiscount());
        });
        return topes;
    }

    BigDecimal topeParaListado(Campaign campana, Map<Empresas, BigDecimal> topes) {
        Empresas par = Empresas.de(campana);
        return par.agencia().equals(par.acreedor()) ? CIEN : topes.getOrDefault(par, BigDecimal.ZERO);
    }

    /**
     * Cuanto autoriza condonar el acreedor a quien gestiona la campana, hoy: el
     * maximo de su mandato vigente, o 0 sin uno. El acreedor que cobra sin
     * agencia no tiene mandato: se autoriza a si mismo, hasta 100.
     */
    public BigDecimal tope(Organization quien, Organization acreedor, LocalDate hoy) {
        if (quien.getId().equals(acreedor.getId())) {
            return CIEN;
        }
        return mandates.findByAgencyAndCreditorAndStatus(quien, acreedor, Mandate.Status.active).stream()
                .filter(m -> m.vigenteAl(hoy))
                .max(Comparator.comparing(Mandate::getValidFrom))
                .map(Mandate::getMaxMoraDiscount)
                .orElse(BigDecimal.ZERO);
    }

    /**
     * El descuento por tramo que manda quien cobra, revisado contra el contrato
     * y contra su tope, como texto JSON para guardar. Vacio ({@code {}}) da
     * null: la campana deja de ofrecer descuento.
     */
    public String normalizar(JsonNode porTramo, BigDecimal tope) {
        if (!porTramo.isObject()) {
            throw invalido();
        }
        Map<String, BigDecimal> leidos = new HashMap<>();
        for (Map.Entry<String, JsonNode> tramo : porTramo.properties()) {
            if (!TRAMOS.contains(tramo.getKey()) || !tramo.getValue().isNumber()) {
                throw invalido();
            }
            BigDecimal valor = porcentajeValido(tramo.getValue().decimalValue());
            if (valor.compareTo(tope) > 0) {
                throw new CarteraInvalida("descuento_sobre_tope", "El tramo " + tramo.getKey() + " ofrece "
                        + valor.stripTrailingZeros().toPlainString() + "%, y el acreedor autoriza hasta "
                        + tope.stripTrailingZeros().toPlainString() + "%");
            }
            leidos.put(tramo.getKey(), valor);
        }
        if (leidos.isEmpty()) {
            return null;
        }
        ObjectNode limpio = json.createObjectNode();
        TRAMOS.stream().filter(leidos::containsKey).forEach(t -> limpio.put(t, leidos.get(t)));
        return limpio.toString();
    }

    /** Un porcentaje del contrato: de 0 a 100, con hasta dos decimales. Null sigue siendo null. */
    public static BigDecimal porcentajeValido(BigDecimal valor) {
        if (valor == null) {
            return null;
        }
        if (valor.signum() < 0 || valor.compareTo(CIEN) > 0 || valor.stripTrailingZeros().scale() > 2) {
            throw invalido();
        }
        return valor;
    }

    /** Lo que la campana ofrece por tramo, como lo guardo {@link #normalizar}. */
    private Map<String, BigDecimal> porTramo(Campaign campana) {
        Map<String, BigDecimal> porTramo = new LinkedHashMap<>();
        try {
            for (Map.Entry<String, JsonNode> tramo : json.readTree(campana.getMoraDiscount()).properties()) {
                porTramo.put(tramo.getKey(), tramo.getValue().decimalValue());
            }
        } catch (Exception e) {
            return Map.of();
        }
        return porTramo;
    }

    private static CarteraInvalida invalido() {
        return new CarteraInvalida("descuento_invalido", "El descuento por tramo va como {\"1-30\": 0, "
                + "\"31-90\": 50, \"91-120\": 100}: cada valor de 0 a 100, con hasta dos decimales");
    }
}
