package com.tbridge.debt.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Lo que pasaria con una planilla si se cargara: la misma respuesta que la
 * carga, sin que nada quede guardado, y cuanto entraria por moneda.
 */
@Schema(description = "La revision de una planilla: lo que pasaria al cargarla, sin guardar nada")
public record RevisionResponse(
        CarteraResponse cartera,
        @Schema(description = "Cuanto entraria, por moneda: las deudas que entran y la suma de sus cargos")
        List<Monto> entrarian
) {

    @Schema(description = "Las deudas de una moneda que entrarian, y cuanto suman")
    public record Monto(
            @Schema(example = "CLP") String moneda,
            @Schema(example = "3") int deudas,
            @Schema(example = "1560000") BigDecimal monto
    ) {
    }

    /** Lo que entran son las deudas que quedan en cobranza: registradas, actualizadas o sin cambios. */
    private static final Set<String> ENTRAN = Set.of("registrada", "actualizada", "sin_cambios");

    /** La respuesta de la ingesta, con lo que suman, por moneda, las deudas de la planilla que entran. */
    public static RevisionResponse de(CarteraResponse respuesta, JsonNode cartera) {
        Set<String> entran = respuesta.resultados() == null ? Set.of() : respuesta.resultados().stream()
                .filter(r -> ENTRAN.contains(r.resultado()))
                .map(CarteraResponse.ResultadoDeuda::idExterno)
                .collect(Collectors.toSet());
        Map<String, BigDecimal> montos = new TreeMap<>();
        Map<String, Integer> deudas = new TreeMap<>();
        for (JsonNode deuda : cartera.path("deudas")) {
            if (!entran.contains(deuda.path("id_externo").asText())) {
                continue;
            }
            String moneda = deuda.path("moneda").asText("CLP");
            BigDecimal suma = BigDecimal.ZERO;
            for (JsonNode cargo : deuda.path("cargos")) {
                suma = suma.add(cargo.path("monto").decimalValue());
            }
            montos.merge(moneda, suma, BigDecimal::add);
            deudas.merge(moneda, 1, Integer::sum);
        }
        return new RevisionResponse(respuesta, montos.entrySet().stream()
                .map(m -> new Monto(m.getKey(), deudas.get(m.getKey()), sinCerosDeMas(m.getValue())))
                .toList());
    }

    /** 1040000, no 1.04E+6: sin los ceros de mas a la derecha, pero sin pasar a notacion cientifica. */
    private static BigDecimal sinCerosDeMas(BigDecimal monto) {
        BigDecimal limpio = monto.stripTrailingZeros();
        return limpio.scale() < 0 ? limpio.setScale(0) : limpio;
    }
}
