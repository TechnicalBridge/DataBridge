package com.tbridge.auth.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Sin SMTP_HOST, el correo va al buzon de prueba y lo demas de SMTP_* se
 * ignora; con SMTP_HOST, el buzon de prueba no se usa.
 */
class BuzonDePruebaTest {

    /** Como queda el entorno con lo que trae un .env y lo que anuncia el compose. */
    private static StandardEnvironment entorno(Map<String, Object> propiedades) {
        StandardEnvironment entorno = new StandardEnvironment();
        //  Lo que application.properties arma a partir del .env.
        Map<String, Object> base = new HashMap<>(Map.of(
                "spring.mail.host", "",
                "spring.mail.port", "587",
                "spring.mail.username", "",
                "spring.mail.password", "",
                "spring.mail.properties.mail.smtp.auth", "true",
                "spring.mail.properties.mail.smtp.starttls.enable", "true"));
        base.putAll(propiedades);
        entorno.getPropertySources().addFirst(new MapPropertySource("aplicacion", base));
        new BuzonDePrueba().postProcessEnvironment(entorno, null);
        return entorno;
    }

    @Test
    void sin_servidor_y_con_buzon_de_prueba_el_correo_va_al_buzon_sin_clave_ni_cifrado() {
        //  Un .env de siempre: SMTP_HOST vacio, y el puerto y la autenticacion de un servidor real.
        StandardEnvironment entorno = entorno(Map.of("app.mail.buzon-de-prueba", "mailpit:1025"));

        assertEquals("mailpit", entorno.getProperty("spring.mail.host"));
        assertEquals("1025", entorno.getProperty("spring.mail.port"), "no el 587 del .env");
        assertEquals("false", entorno.getProperty("spring.mail.properties.mail.smtp.auth"));
        assertEquals("false", entorno.getProperty("spring.mail.properties.mail.smtp.starttls.enable"));
        assertEquals("false", entorno.getProperty("spring.mail.properties.mail.smtp.ssl.enable"));
    }

    @Test
    void con_un_servidor_configurado_el_buzon_de_prueba_no_se_usa() {
        StandardEnvironment entorno = entorno(Map.of(
                "spring.mail.host", "smtp.gmail.com",
                "spring.mail.username", "equipo@gmail.com",
                "app.mail.buzon-de-prueba", "mailpit:1025"));

        assertEquals("smtp.gmail.com", entorno.getProperty("spring.mail.host"));
        assertEquals("587", entorno.getProperty("spring.mail.port"));
        assertEquals("equipo@gmail.com", entorno.getProperty("spring.mail.username"));
        assertEquals("true", entorno.getProperty("spring.mail.properties.mail.smtp.auth"));
        assertFalse(entorno.getPropertySources().contains(BuzonDePrueba.FUENTE));
    }

    @Test
    void sin_servidor_y_sin_buzon_no_hay_a_donde_mandar_y_todo_queda_como_estaba() {
        StandardEnvironment entorno = entorno(Map.of());

        assertEquals("", entorno.getProperty("spring.mail.host"));
        assertFalse(entorno.getPropertySources().contains(BuzonDePrueba.FUENTE));
    }

    @Test
    void un_buzon_sin_puerto_usa_el_de_mailpit() {
        assertEquals("1025", entorno(Map.of("app.mail.buzon-de-prueba", "mailpit")).getProperty("spring.mail.port"));
    }

    @Test
    void la_clave_de_un_servidor_real_no_se_le_manda_al_buzon_de_prueba() {
        //  Alguien dejo la clave en el .env y saco el SMTP_HOST para volver al buzon de prueba.
        StandardEnvironment entorno = entorno(Map.of(
                "spring.mail.username", "equipo@gmail.com",
                "spring.mail.password", "una-clave-de-aplicacion",
                "app.mail.buzon-de-prueba", "mailpit:1025"));

        assertEquals("", entorno.getProperty("spring.mail.username"));
        assertEquals("", entorno.getProperty("spring.mail.password"));
    }
}
