package com.tbridge.debt.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

/**
 * Al arrancar, si se esta cambiando CIFRADO_LLAVE (hay CIFRADO_LLAVE_ANTERIOR),
 * vuelve a cifrar con la llave nueva cada secreto que todavia esta con la de
 * antes. Asi, cambiar la llave no obliga a cada sistema conectado a volver a
 * suscribirse (#258).
 *
 * <p>Todo va en una sola transaccion y con las filas bloqueadas: si un secreto
 * no se abre con ninguna de las dos llaves, o una fila cambio por debajo, no se
 * cambia ninguno y ms-debt no arranca, para que se note de inmediato y la base
 * quede como estaba. Con SQL sobre la columna, como {@link CifrarSecretosGuardados}.
 */
@Component
public class RecifrarSecretosGuardados implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RecifrarSecretosGuardados.class);

    private final JdbcTemplate jdbc;
    private final Cifrado cifrado;

    public RecifrarSecretosGuardados(JdbcTemplate jdbc, Cifrado cifrado) {
        this.jdbc = jdbc;
        this.cifrado = cifrado;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!cifrado.tieneAnterior()) return;
        List<Map<String, Object>> cifrados = jdbc.queryForList(
                "SELECT id, secret FROM subscriptions WHERE secret LIKE ? FOR UPDATE", Cifrado.PREFIJO + "%");
        int cambiados = 0;
        for (Map<String, Object> fila : cifrados) {
            String guardado = (String) fila.get("secret");
            String nuevo;
            try {
                nuevo = cifrado.conLaLlaveActual(guardado);
            } catch (IllegalStateException e) {
                throw new IllegalStateException("El secreto de la suscripcion " + fila.get("id")
                        + " no se abre ni con CIFRADO_LLAVE ni con CIFRADO_LLAVE_ANTERIOR: no se cambio ningun secreto", e);
            }
            if (nuevo == null) continue;
            //  Solo si sigue siendo el que se leyo: si no, alguien lo cambio por debajo.
            if (jdbc.update("UPDATE subscriptions SET secret = ? WHERE id = ? AND secret = ?",
                    nuevo, fila.get("id"), guardado) != 1) {
                throw new IllegalStateException("La suscripcion " + fila.get("id")
                        + " cambio mientras se volvia a cifrar: no se cambio ningun secreto");
            }
            cambiados++;
        }
        if (cambiados > 0) {
            log.info("Cifrados con la llave nueva {} secretos de suscripciones que estaban con la anterior. "
                    + "Ya se puede sacar CIFRADO_LLAVE_ANTERIOR del .env", cambiados);
        } else {
            log.warn("Ningun secreto de suscripcion usa CIFRADO_LLAVE_ANTERIOR: se puede sacar del .env");
        }
    }
}
