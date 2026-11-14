package com.tbridge.auth.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.util.LinkedHashMap;
import java.util.Map;

/** Resuelve el transporte antes de la autoconfiguración de Spring Mail. */
public class BuzonDePrueba implements EnvironmentPostProcessor, Ordered {
    static final String FUENTE = "buzonDePrueba";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment entorno, SpringApplication aplicacion) {
        String modo = TransporteCorreo.seleccionar(entorno);
        Map<String, Object> propiedades = new LinkedHashMap<>();
        propiedades.put("app.mail.transport", modo.equals("none") ? "" : modo);
        if (modo.equals("mailpit")) {
            String buzon = entorno.getProperty("app.mail.buzon-de-prueba", "").strip();
            if (buzon.isEmpty()) throw new IllegalArgumentException("MAIL_TRANSPORT=mailpit requiere CORREO_BUZON_DE_PRUEBA");
            String[] partes = buzon.split(":", 2);
            if (partes[0].isBlank()) throw new IllegalArgumentException("CORREO_BUZON_DE_PRUEBA requiere un host");
            propiedades.put("spring.mail.host", partes[0]);
            propiedades.put("spring.mail.port", partes.length > 1 && !partes[1].isBlank() ? partes[1] : "1025");
            propiedades.put("spring.mail.username", "");
            propiedades.put("spring.mail.password", "");
            for (String opcion : new String[]{"auth", "starttls.enable", "starttls.required", "ssl.enable"}) {
                propiedades.put("spring.mail.properties.mail.smtp." + opcion, "false");
            }
            entorno.getPropertySources().addFirst(new MapPropertySource(FUENTE, propiedades));
        } else {
            entorno.getPropertySources().addFirst(new MapPropertySource("transporteCorreo", propiedades));
        }
    }

    @Override
    public int getOrder() { return Ordered.LOWEST_PRECEDENCE; }
}
