package com.tbridge.payments.integration;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BaseDePruebasSeguraTest {
    @Test
    void permite_solo_la_base_exacta_del_servicio_con_parametros() {
        for (String url : new String[]{"jdbc:mysql://localhost:3306/tb_payments_pruebas",
                "jdbc:mysql://127.0.0.1:3308/tb_payments_pruebas?useSSL=false&serverTimezone=America/Santiago"}) {
            assertEquals(url, BaseDePruebasSegura.validar(url));
        }
    }

    @Test
    void rechaza_ausente_invalida_productiva_otro_servicio_y_sufijos() {
        for (String url : new String[]{null, "", "invalida", "jdbc:mysql://localhost/tb_payments",
                "jdbc:mysql://localhost/tb_auth_pruebas", "jdbc:mysql://localhost/tb_debt_pruebas",
                "jdbc:mysql://localhost/tb_payments_pruebas_extra", "jdbc:mysql://localhost/tb_payments_pruebas/otra",
                "jdbc:mysql://localhost/tb_payments_pruebas#fragmento", "jdbc:mysql://localhost/tb_payments_pruebas;otra"}) {
            var error = assertThrows(IllegalStateException.class, () -> BaseDePruebasSegura.validar(url));
            assertFalse(error.getMessage().contains("localhost"));
        }
    }
}

