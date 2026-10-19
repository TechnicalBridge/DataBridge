package com.tbridge.auth.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;

/** Valida los secretos antes de crear conexiones o servicios, sin mostrar sus valores. */
public class ConfiguracionObligatoria implements EnvironmentPostProcessor, Ordered {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment entorno, SpringApplication aplicacion) {
        exigir(entorno, "spring.datasource.password", "DB_PASSWORD o MYSQL_PASSWORD");
        exigir(entorno, "jwt.secret", "JWT_SECRET");
        exigir(entorno, "app.internal-key", "INTERNAL_KEY");
    }

    private static void exigir(ConfigurableEnvironment entorno, String propiedad, String variable) {
        String valor;
        try {
            valor = entorno.getRequiredProperty(propiedad);
        } catch (IllegalArgumentException | IllegalStateException falta) {
            throw faltante(variable);
        }
        if (valor.isBlank()) {
            throw faltante(variable);
        }
    }

    private static IllegalStateException faltante(String variable) {
        return new IllegalStateException("Falta " + variable
                + ". Revisa el .env de TB_web o las variables de la terminal.");
    }

    /** Despues de cargar application.properties, el perfil y el .env. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE - 1;
    }
}
