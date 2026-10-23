package com.tbridge.debt.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** El modelo de negocio, tal cual: p(G) por tramo sobre todo G, y P(G) = G · p(G). */
class ModeloDeNegocioTest {

    private static void tramo(long recuperado, String porcentaje, long comision) {
        assertEquals(new BigDecimal(porcentaje), ModeloDeNegocio.porcentaje(recuperado), "p(" + recuperado + ")");
        assertEquals(comision, ModeloDeNegocio.comision(recuperado), "P(" + recuperado + ")");
    }

    @Test
    void cada_tramo_con_su_porcentaje_y_sus_bordes() {
        tramo(800_000, "6", 48_000);              //  a la empresa le quedan 752.000
        tramo(1_000_000, "6", 60_000);            //  el borde va en el tramo de abajo
        tramo(1_000_001, "10", 100_000);          //  100.000,1, redondeado a pesos
        tramo(5_000_000, "10", 500_000);
        tramo(5_000_001, "12", 600_000);
        tramo(10_000_000, "12", 1_200_000);
        tramo(10_000_001, "15", 1_500_000);
        tramo(12_000_000, "15", 1_800_000);
    }

    @Test
    void sin_nada_recuperado_no_hay_tramo_ni_comision() {
        tramo(0, "0", 0);
        assertTrue(ModeloDeNegocio.tramoDe(0).isEmpty());
    }

    @Test
    void se_aplica_tal_cual_sobre_todo_lo_recuperado_aunque_en_el_borde_haya_un_salto() {
        //  Asi lo decidio el equipo: con un peso mas, la empresa se queda con menos.
        assertEquals(940_000, 1_000_000 - ModeloDeNegocio.comision(1_000_000));
        assertEquals(900_001, 1_000_001 - ModeloDeNegocio.comision(1_000_001));
    }

    @Test
    void el_medio_peso_se_redondea_hacia_arriba() {
        assertEquals(1, ModeloDeNegocio.comision(9));                 //  0,54
        assertEquals(2, ModeloDeNegocio.comision(25));                //  1,5
        assertEquals(630_000, ModeloDeNegocio.comision(5_250_001));   //  630.000,12, al 12 %
    }
}
