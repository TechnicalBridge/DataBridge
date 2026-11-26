package com.tbridge.debt.dto.response;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DebtDetailResponseTest {

    @Test
    void muestra_las_referencias_de_la_cartera_sin_perder_sus_etiquetas() {
        assertEquals(Map.of("contrato", "CTR-45", "propiedad", "Casa 2"),
                DebtDetailResponse.referencias("{\"contrato\":\"CTR-45\",\"propiedad\":\"Casa 2\"}"));
    }

    @Test
    void no_falla_con_referencias_ausentes_o_anteriores_invalidas() {
        assertEquals(Map.of(), DebtDetailResponse.referencias(null));
        assertEquals(Map.of(), DebtDetailResponse.referencias("{mal json"));
        assertEquals(Map.of(), DebtDetailResponse.referencias("[]"));
    }
}
