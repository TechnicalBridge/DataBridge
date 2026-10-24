package com.tbridge.debt.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * El modelo de negocio de DataBridge (#200): cada empresa usuaria le paga a
 * DataBridge un porcentaje de lo que recupera con la plataforma, y el
 * porcentaje sube con lo recuperado.
 *
 * <pre>
 *          ⎧  6 %   si 0 &lt; G ≤ $1.000.000
 *   p(G) = ⎨ 10 %   si $1.000.000 &lt; G ≤ $5.000.000
 *          ⎪ 12 %   si $5.000.000 &lt; G ≤ $10.000.000
 *          ⎩ 15 %   si G &gt; $10.000.000
 *
 *   P(G) = G · p(G)            lo que la empresa le paga a DataBridge
 *   G_empresa = G − P(G)       lo que le queda a la empresa
 * </pre>
 *
 * <p>Es el modelo matematico del equipo y se aplica <b>tal cual</b>: el
 * porcentaje del tramo va sobre todo G, no por tramos escalonados. Se sabe
 * que en cada cambio de tramo hay un salto (con $1.000.000 la empresa se queda
 * con $940.000; con $1.000.001, con $900.001), y se decidio mantenerlo asi.
 *
 * <p>G es lo recuperado por una empresa en un mes calendario, en pesos: ver
 * {@link ComisionService}.
 */
public final class ModeloDeNegocio {

    /** Un tramo: desde (sin incluir) y hasta (incluido) cuanto se recupero, y su porcentaje. Sin tope, el ultimo. */
    public record Tramo(long desde, Long hasta, BigDecimal porcentaje) {

        boolean incluye(long recuperado) {
            return recuperado > desde && (hasta == null || recuperado <= hasta);
        }
    }

    public static final List<Tramo> TRAMOS = List.of(
            new Tramo(0, 1_000_000L, new BigDecimal("6")),
            new Tramo(1_000_000, 5_000_000L, new BigDecimal("10")),
            new Tramo(5_000_000, 10_000_000L, new BigDecimal("12")),
            new Tramo(10_000_000, null, new BigDecimal("15")));

    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    private ModeloDeNegocio() {
    }

    /** El tramo en que cae lo recuperado. Sin nada recuperado, ninguno. */
    public static Optional<Tramo> tramoDe(long recuperado) {
        return TRAMOS.stream().filter(t -> t.incluye(recuperado)).findFirst();
    }

    /** p(G), en porcentaje: 6, 10, 12 o 15. Cero si no se recupero nada. */
    public static BigDecimal porcentaje(long recuperado) {
        return tramoDe(recuperado).map(Tramo::porcentaje).orElse(BigDecimal.ZERO);
    }

    /** P(G) = G · p(G), en pesos enteros: el medio peso se redondea hacia arriba. */
    public static long comision(long recuperado) {
        return BigDecimal.valueOf(recuperado).multiply(porcentaje(recuperado))
                .divide(CIEN, 0, RoundingMode.HALF_UP).longValueExact();
    }
}
