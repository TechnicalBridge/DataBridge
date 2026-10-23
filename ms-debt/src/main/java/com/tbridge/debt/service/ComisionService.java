package com.tbridge.debt.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.debt.dto.response.ComisionResponse;
import com.tbridge.debt.dto.response.ComisionesResponse;
import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.model.Campaign;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.DebtEvent;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.DebtEventRepository;
import com.tbridge.debt.security.JwtPrincipal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Lo que cada empresa le paga a DataBridge (#200), segun el {@link ModeloDeNegocio}.
 *
 * <p><b>G es lo recuperado por la empresa en un mes calendario</b> (hora de
 * Chile), en pesos: la suma de los pagos abonados a las deudas que ella cargo,
 * de todas sus campanas. Un pago en UF cuenta por los pesos que cobro la
 * pasarela. Un pago duplicado, fallido o anulado no suma, porque nunca se abono.
 *
 * <p><b>La comision la paga quien cargo la deuda</b> en DataBridge: la agencia, o
 * el acreedor que cobra directo. Asi un pago nunca se cobra dos veces, aunque la
 * deuda aparezca en la cartera del acreedor y en la de su agencia.
 *
 * <p>Se calcula cada vez y no se guarda: lo que cambia es lo que ya esta en la
 * base (los pagos abonados).
 */
@Service
@Transactional(readOnly = true)
public class ComisionService {

    private static final Logger log = LoggerFactory.getLogger(ComisionService.class);
    static final ZoneId CHILE = ZoneId.of("America/Santiago");

    private final DebtEventRepository eventos;
    private final DebtService debts;
    private final ObjectMapper json;

    public ComisionService(DebtEventRepository eventos, DebtService debts, ObjectMapper json) {
        this.eventos = eventos;
        this.debts = debts;
        this.json = json;
    }

    /** La de la empresa de la sesion, en ese mes (sin mes, el de hoy). */
    public ComisionResponse deLaEmpresa(JwtPrincipal user, YearMonth mes) {
        if (user == null || !user.isCreditor()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Solo una empresa ve lo que le paga a DataBridge");
        }
        Organization empresa = debts.organizacionDe(user);
        YearMonth elMes = mes != null ? mes : YearMonth.now(CHILE);
        return respuesta(elMes, eventos.entre(DebtEvent.Type.payment_applied, inicio(elMes),
                inicio(elMes.plusMonths(1))).stream()
                .filter(pago -> empresa.getRut().equals(empresaRut(pago))).toList());
    }

    /** La de cada empresa con pagos en ese mes, para el panel del admin: lo que DataBridge factura. */
    public ComisionesResponse deTodas(YearMonth mes) {
        YearMonth elMes = mes != null ? mes : YearMonth.now(CHILE);
        Map<String, List<DebtEvent>> porEmpresa = new LinkedHashMap<>();
        for (DebtEvent pago : eventos.entre(DebtEvent.Type.payment_applied, inicio(elMes), inicio(elMes.plusMonths(1)))) {
            porEmpresa.computeIfAbsent(empresaRut(pago), k -> new ArrayList<>()).add(pago);
        }
        List<ComisionesResponse.Empresa> empresas = new ArrayList<>();
        porEmpresa.forEach((empresa, pagos) -> {
            long recuperado = pagos.stream().mapToLong(this::enPesos).sum();
            long comision = ModeloDeNegocio.comision(recuperado);
            empresas.add(new ComisionesResponse.Empresa(empresa, empresaNombre(pagos.getLast()), recuperado,
                    pagos.size(), ModeloDeNegocio.porcentaje(recuperado), comision, recuperado - comision));
        });
        empresas.sort(Comparator.comparingLong(ComisionesResponse.Empresa::comision).reversed());
        return new ComisionesResponse(elMes.atDay(1), empresas,
                empresas.stream().mapToLong(ComisionesResponse.Empresa::recuperado).sum(),
                empresas.stream().mapToLong(ComisionesResponse.Empresa::comision).sum());
    }

    ComisionResponse respuesta(YearMonth mes, List<DebtEvent> pagos) {
        long recuperado = 0;
        Map<String, long[]> porCampana = new LinkedHashMap<>();
        Map<String, String> nombres = new LinkedHashMap<>();
        for (DebtEvent pago : pagos) {
            long pesos = enPesos(pago);
            recuperado += pesos;
            Campaign campana = pago.getDebt().getCampaign();
            JsonNode detalle = leer(pago.getDetail());
            boolean atribuido = detalle.path("empresa_rut").isTextual();
            String clave = atribuido ? detalle.path("campana_id").asText("")
                    : campana == null ? "" : campana.getExternalId();
            nombres.putIfAbsent(clave, atribuido ? detalle.path("campana_nombre").asText("Sin campaña")
                    : campana == null ? "Sin campaña" : campana.getName());
            long[] suma = porCampana.computeIfAbsent(clave, k -> new long[2]);
            suma[0] += pesos;
            suma[1]++;
        }
        List<ComisionResponse.PorCampana> campanas = new ArrayList<>();
        porCampana.forEach((clave, suma) -> campanas.add(new ComisionResponse.PorCampana(
                clave.isEmpty() ? null : clave, nombres.get(clave), suma[0], (int) suma[1])));
        campanas.sort(Comparator.comparingLong(ComisionResponse.PorCampana::recuperado).reversed());

        long total = recuperado;
        ModeloDeNegocio.Tramo vigente = ModeloDeNegocio.tramoDe(total).orElse(null);
        List<ComisionResponse.TramoResponse> tramos = ModeloDeNegocio.TRAMOS.stream()
                .map(t -> new ComisionResponse.TramoResponse(t.desde(), t.hasta(), t.porcentaje(), t == vigente))
                .toList();
        long comision = ModeloDeNegocio.comision(total);
        return new ComisionResponse(mes.atDay(1), total, pagos.size(), ModeloDeNegocio.porcentaje(total), comision,
                total - comision, campanas, tramos);
    }

    /**
     * Los pesos de un pago abonado: los que cobro la pasarela (montoClp, en su
     * detalle). Sin ese dato, el monto si la deuda es en pesos, o el monto en
     * UF por el valor de la UF de ese pago.
     */
    long enPesos(DebtEvent pago) {
        JsonNode detalle = leer(pago.getDetail());
        JsonNode montoClp = campo(detalle, "monto_clp", "montoClp");
        if (montoClp.isNumber()) {
            return montoClp.asLong();
        }
        BigDecimal monto = pago.getAmount();
        if (monto == null) {
            return 0;
        }
        if (pago.getCurrency() == null || pago.getCurrency() == Debt.Currency.CLP) {
            return monto.setScale(0, RoundingMode.HALF_UP).longValueExact();
        }
        JsonNode valorUf = campo(detalle, "valor_uf", "valorUf");
        if (valorUf.isNumber()) {
            return monto.multiply(valorUf.decimalValue()).setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
        }
        log.warn("El pago {} en UF no trae los pesos ni la UF del dia: no suma a la comision", pago.getReference());
        return 0;
    }

    private String empresaRut(DebtEvent pago) {
        return leer(pago.getDetail()).path("empresa_rut")
                .asText(pago.getDebt().getLastBatch().getSender().getRut());
    }

    private String empresaNombre(DebtEvent pago) {
        return leer(pago.getDetail()).path("empresa_nombre")
                .asText(pago.getDebt().getLastBatch().getSender().getTradeName());
    }

    private static JsonNode campo(JsonNode detalle, String nombre, String anterior) {
        return detalle.hasNonNull(nombre) ? detalle.path(nombre) : detalle.path(anterior);
    }

    private JsonNode leer(String detalle) {
        if (detalle == null || detalle.isBlank()) {
            return json.createObjectNode();
        }
        try {
            JsonNode leido = json.readTree(detalle);
            return leido != null && leido.isObject() ? leido : json.createObjectNode();
        } catch (Exception e) {
            return json.createObjectNode();
        }
    }

    private static Instant inicio(YearMonth mes) {
        return mes.atDay(1).atStartOfDay(CHILE).toInstant();
    }
}
