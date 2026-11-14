package com.tbridge.debt.service;

import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.security.JwtPrincipal;
import com.tbridge.debt.dto.response.ResumenResponse;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.DebtEvent;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.DebtEventRepository;
import com.tbridge.debt.repository.DebtRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Los numeros de UNA cartera.
 *
 * <p>Parte siempre de la organizacion de la sesion: no existe la posibilidad de
 * pedir el resumen "de todos". La version anterior sumaba {@code findAll()} y
 * le mostraba a cada acreedor el total de la plataforma.
 */
@Service
@Transactional(readOnly = true)
public class AnalyticsService {

    private static final ZoneId CHILE = ZoneId.of("America/Santiago");
    private static final int DIAS = 30;

    private final DebtRepository debts;
    private final DebtEventRepository eventos;
    private final DebtService servicio;

    public AnalyticsService(DebtRepository debts, DebtEventRepository eventos, DebtService servicio) {
        this.debts = debts;
        this.eventos = eventos;
        this.servicio = servicio;
    }

    /** El resumen de la cartera de la empresa de la sesion, y de nadie mas. */
    public ResumenResponse resumenPara(JwtPrincipal user) {
        if (user == null || !user.isCreditor()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Solo el acreedor ve el resumen");
        }
        return resumen(servicio.organizacionDe(user));
    }

    ResumenResponse resumen(Organization organizacion) {
        List<Debt> cartera = debts.carteraDe(organizacion);

        Map<String, BigDecimal[]> porMoneda = new LinkedHashMap<>();
        Map<String, ResumenResponse.PorAcreedor> porAcreedor = new LinkedHashMap<>();
        int activas = 0;
        int pagadas = 0;
        int retiradas = 0;
        int enConvenio = 0;

        for (Debt deuda : cartera) {
            BigDecimal saldo = servicio.saldo(deuda);
            BigDecimal diferenciaConMontoOriginal = deuda.getOriginalAmount().subtract(saldo);
            String moneda = deuda.getCurrency().name();

            BigDecimal[] acumulado = porMoneda.computeIfAbsent(moneda,
                    k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            acumulado[0] = acumulado[0].add(saldo);
            acumulado[1] = acumulado[1].add(diferenciaConMontoOriginal);

            // Para cada acreedor: saldo y diferencia con el monto original, por moneda.
            porAcreedor.merge(deuda.getCreditor().getRut() + "|" + moneda,
                    new ResumenResponse.PorAcreedor(deuda.getCreditor().getTradeName(), moneda, saldo, diferenciaConMontoOriginal),
                    (antes, esta) -> antes.sumar(esta.saldo(), esta.recuperado()));

            switch (deuda.getStatus()) {
                case paid -> pagadas++;
                case withdrawn -> retiradas++;
                case repacted -> { enConvenio++; activas++; }
                default -> activas++;
            }
        }

        List<ResumenResponse.PorMoneda> monedas = new ArrayList<>();
        porMoneda.forEach((moneda, valores) -> {
            BigDecimal origen = valores[0].add(valores[1]);
            BigDecimal tasa = origen.compareTo(BigDecimal.ZERO) == 0
                    ? BigDecimal.ZERO
                    : valores[1].multiply(BigDecimal.valueOf(100)).divide(origen, 1, RoundingMode.HALF_UP);
            monedas.add(new ResumenResponse.PorMoneda(moneda, valores[0], valores[1], tasa));
        });

        return new ResumenResponse(organizacion.getTradeName(), organizacion.getRut(), cartera.size(), activas,
                enConvenio, pagadas, retiradas, monedas, new ArrayList<>(porAcreedor.values()),
                recuperadoPorDia(cartera));
    }

    /**
     * Lo que entro cada dia de los ultimos 30, segun los pagos aplicados. Dias
     * en Chile, y con ceros en los dias sin pagos: un grafico que se salta los
     * dias vacios muestra una tendencia que no existe.
     */
    private Map<String, List<ResumenResponse.PuntoDiario>> recuperadoPorDia(List<Debt> cartera) {
        LocalDate hoy = LocalDate.now(CHILE);
        LocalDate desde = hoy.minusDays(DIAS - 1L);
        Map<String, TreeMap<LocalDate, BigDecimal>> porMoneda = new LinkedHashMap<>();
        if (!cartera.isEmpty()) {
            Instant inicio = desde.atStartOfDay(CHILE).toInstant();
            for (DebtEvent pago : eventos.findByDebtInAndTypeAndOccurredAtAfter(
                    cartera, DebtEvent.Type.payment_applied, inicio)) {
                if (pago.getAmount() == null || pago.getCurrency() == null) {
                    continue;
                }
                LocalDate dia = pago.getOccurredAt().atZone(CHILE).toLocalDate();
                porMoneda.computeIfAbsent(pago.getCurrency().name(), k -> new TreeMap<>())
                        .merge(dia, pago.getAmount(), BigDecimal::add);
            }
        }
        Map<String, List<ResumenResponse.PuntoDiario>> series = new LinkedHashMap<>();
        porMoneda.forEach((moneda, dias) -> {
            List<ResumenResponse.PuntoDiario> puntos = new ArrayList<>();
            for (LocalDate dia = desde; !dia.isAfter(hoy); dia = dia.plusDays(1)) {
                puntos.add(new ResumenResponse.PuntoDiario(dia.toString(), dias.getOrDefault(dia, BigDecimal.ZERO)));
            }
            series.put(moneda, puntos);
        });
        return series;
    }
}
