package com.tbridge.debt.integration;

/** Valida antes de registrar el datasource o ejecutar Flyway. Nunca imprime la URL. */
final class BaseDePruebasSegura {
    private BaseDePruebasSegura() {}

    static String validar(String url) {
        if (url == null || !url.matches("jdbc:mysql://[a-zA-Z0-9.\\[\\]:-]+/tb_debt_pruebas(?:\\?[^#\\r\\n]*)?")) {
            throw new IllegalStateException("DB_TEST_URL debe identificar exclusivamente tb_debt_pruebas");
        }
        return url;
    }
}

