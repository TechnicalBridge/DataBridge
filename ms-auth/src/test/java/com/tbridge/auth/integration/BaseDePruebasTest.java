package com.tbridge.auth.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** La proteccion que impide borrar las tablas de una base real. */
class BaseDePruebasTest {

    @Test
    void sin_url_no_hay_nada_que_validar() {
        assertDoesNotThrow(() -> BaseIntegracionMySql.validarBaseDePruebas(null));
        assertDoesNotThrow(() -> BaseIntegracionMySql.validarBaseDePruebas("   "));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:mysql://127.0.0.1:3308/tb_auth_pruebas?createDatabaseIfNotExist=true&serverTimezone=America/Santiago",
            "jdbc:mysql://127.0.0.1:3308/tb_auth_test",
            "jdbc:mysql://db:3306/TB_AUTH_PRUEBAS?useSSL=false",
            "jdbc:mysql://127.0.0.1:3308/tb_auth_test;createDatabaseIfNotExist=true"
    })
    void acepta_bases_cuyo_nombre_termina_en_pruebas_o_test(String url) {
        assertDoesNotThrow(() -> BaseIntegracionMySql.validarBaseDePruebas(url));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "jdbc:mysql://127.0.0.1:3308/tb_auth",
            "jdbc:mysql://127.0.0.1:3308/tb_auth?useSSL=false",
            "jdbc:mysql://127.0.0.1:3308/tb_auth_pruebas_extra",
            "jdbc:mysql://127.0.0.1:3308",
            "jdbc:mysql://127.0.0.1:3308/"
    })
    void rechaza_bases_que_no_son_de_pruebas(String url) {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> BaseIntegracionMySql.validarBaseDePruebas(url));

        assertTrue(error.getMessage().contains("_pruebas"), error.getMessage());
        assertTrue(error.getMessage().contains("_test"), error.getMessage());
    }

    @Test
    void el_error_dice_la_base_y_como_corregirla_sin_mostrar_la_url() {
        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> BaseIntegracionMySql.validarBaseDePruebas(
                        "jdbc:mysql://127.0.0.1:3308/tb_auth?useSSL=false&password=no-debe-salir"));

        assertTrue(error.getMessage().contains("tb_auth"), error.getMessage());
        assertTrue(error.getMessage().contains("DB_TEST_URL"), error.getMessage());
        assertTrue(error.getMessage().contains("Testcontainers"), error.getMessage());
        assertFalse(error.getMessage().contains("useSSL"), error.getMessage());
        assertFalse(error.getMessage().contains("no-debe-salir"), error.getMessage());
    }

    @Test
    void nombre_de_base_ignora_parametros_y_opciones() {
        assertEquals("tb_auth_pruebas", BaseIntegracionMySql.nombreDeBase(
                "jdbc:mysql://127.0.0.1:3308/tb_auth_pruebas?useSSL=false"));
        assertEquals("tb_auth_test", BaseIntegracionMySql.nombreDeBase(
                "jdbc:mysql://127.0.0.1:3308/tb_auth_test;createDatabaseIfNotExist=true"));
        assertEquals("", BaseIntegracionMySql.nombreDeBase("jdbc:mysql://127.0.0.1:3308"));
    }
}
