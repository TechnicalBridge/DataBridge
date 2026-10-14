package com.tbridge.auth.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Sin servidor de correo configurado, el buzon de prueba.
 *
 * <p>En Docker, ms-auth tiene a mano un buzon de prueba (Mailpit), que el
 * compose le anuncia en {@code CORREO_BUZON_DE_PRUEBA}. Si no hay
 * {@code SMTP_HOST}, el correo va ahi, y lo demas de {@code SMTP_*} se ignora:
 * el puerto, la autenticacion y STARTTLS de un {@code .env} son los de un
 * servidor real, y con el buzon de prueba no calzan. Con {@code SMTP_HOST}, el
 * buzon de prueba no se usa.
 *
 * <p>Sin las dos cosas (ms-auth corriendo con Maven, sin nada configurado) no
 * se manda nada: los codigos y los enlaces quedan en el log.
 *
 * <p>Corre antes de que arranque el contexto, y lo unico que hace es dejar
 * escritas las propiedades {@code spring.mail.*} del buzon de prueba: el resto
 * del servicio no sabe que existe.
 */
public class BuzonDePrueba implements EnvironmentPostProcessor, Ordered {

    static final String FUENTE = "buzonDePrueba";
    private static final String PUERTO_POR_OMISION = "1025";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment entorno, SpringApplication aplicacion) {
        String servidor = entorno.getProperty("spring.mail.host", "");
        String buzon = entorno.getProperty("app.mail.buzon-de-prueba", "");
        if (!servidor.isBlank() || buzon.isBlank()) {
            return;
        }
        String[] partes = buzon.trim().split(":", 2);
        Map<String, Object> delBuzon = new LinkedHashMap<>();
        delBuzon.put("spring.mail.host", partes[0]);
        delBuzon.put("spring.mail.port", partes.length > 1 && !partes[1].isBlank() ? partes[1] : PUERTO_POR_OMISION);
        delBuzon.put("spring.mail.username", "");
        delBuzon.put("spring.mail.password", "");
        delBuzon.put("spring.mail.properties.mail.smtp.auth", "false");
        delBuzon.put("spring.mail.properties.mail.smtp.starttls.enable", "false");
        delBuzon.put("spring.mail.properties.mail.smtp.ssl.enable", "false");
        entorno.getPropertySources().addFirst(new MapPropertySource(FUENTE, delBuzon));
    }

    /** Despues de que se leyo application.properties y el .env: recien ahi se sabe si hay SMTP_HOST. */
    @Override
    public int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }
}
