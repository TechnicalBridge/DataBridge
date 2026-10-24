package com.tbridge.debt.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * Lo que rige en un servicio, para la pantalla Reglas del panel: que es,
 * cuanto vale y de donde sale. Nunca una clave ni un secreto.
 */
@Schema(description = "Las reglas que rigen en un servicio, agrupadas")
public record ReglasResponse(
        @Schema(example = "ms-debt") String servicio,
        List<Grupo> grupos
) {

    @Schema(description = "Un grupo de reglas")
    public record Grupo(@Schema(example = "Cobranza") String nombre, List<Regla> reglas) {
    }

    /**
     * Una regla. {@code variable} es la que la cambia; sin variable, la fija la
     * ley o el codigo, y {@code nota} dice cual. {@code lista} trae varios
     * valores cuando son mas de uno, como los feriados.
     */
    @Schema(description = "Una regla, con lo que vale y de donde sale")
    public record Regla(
            @Schema(example = "Una deuda entra a cobranza con") String que,
            @Schema(example = "30 días de mora, o más") String valor,
            @Schema(description = "Varios valores, cuando son mas de uno") List<String> lista,
            @Schema(example = "MIN_DIAS_MORA", nullable = true) String variable,
            @Schema(example = "Ley 19.496, art. 37", nullable = true) String nota
    ) {

        /** Una que cambia una variable. */
        public static Regla de(String que, String valor, String variable) {
            return new Regla(que, valor, List.of(), variable, null);
        }

        /** Una que no se configura: la fija la ley o el codigo. */
        public static Regla fija(String que, String valor, String nota) {
            return new Regla(que, valor, List.of(), null, nota);
        }
    }
}
