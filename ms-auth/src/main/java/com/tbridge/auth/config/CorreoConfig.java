package com.tbridge.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.util.StringUtils;

import java.time.Duration;

/** SMTP usa la autoconfiguración de Spring Boot; Brevo es un transporte opcional. */
@Configuration
public class CorreoConfig {
    @Bean
    Object validarCorreo(Environment env) {
        String modo = TransporteCorreo.seleccionar(env);
        if (modo.equals("brevo")) {
            requerido(env, "app.mail.brevo.api-key", "MAIL_TRANSPORT=brevo requiere BREVO_API_KEY");
        }
        if (modo.equals("smtp")) {
            requerido(env, "spring.mail.host", "MAIL_TRANSPORT=smtp requiere SMTP_HOST");
            if (env.getProperty("spring.mail.properties.mail.smtp.auth", Boolean.class, false)) {
                requerido(env, "spring.mail.username", "SMTP_AUTH=true requiere SMTP_USER y SMTP_PASSWORD");
                requerido(env, "spring.mail.password", "SMTP_AUTH=true requiere SMTP_USER y SMTP_PASSWORD");
            }
            if (env.getProperty("spring.mail.properties.mail.smtp.ssl.enable", Boolean.class, false)
                    && env.getProperty("spring.mail.properties.mail.smtp.starttls.enable", Boolean.class, false)) {
                throw new IllegalArgumentException("SMTP_SSL y SMTP_STARTTLS no pueden activarse simultáneamente");
            }
            int puerto = env.getProperty("spring.mail.port", Integer.class, 587);
            if (puerto < 1 || puerto > 65535) throw new IllegalArgumentException("SMTP_PORT fuera de rango");
        }
        if (!modo.equals("none")) {
            for (String propiedad : new String[]{"connectiontimeout", "timeout", "writetimeout"}) {
                if (env.getProperty("spring.mail.properties.mail.smtp." + propiedad, Long.class, 10000L) <= 0) {
                    throw new IllegalArgumentException("SMTP_TIMEOUT_MS debe ser positivo");
                }
            }
        }
        return new Object();
    }

    private static void requerido(Environment env, String propiedad, String mensaje) {
        if (!StringUtils.hasText(env.getProperty(propiedad))) throw new IllegalArgumentException(mensaje);
    }

    @Bean
    @Conditional(ConClaveDeBrevo.class)
    JavaMailSender correoPorBrevo(@Value("${app.mail.brevo.url}") String url,
                                 @Value("${app.mail.brevo.api-key}") String clave,
                                 @Value("${app.mail.timeout-ms:10000}") long espera,
                                 ObjectMapper json) {
        return new CorreoPorBrevo(url, clave.strip(), Duration.ofMillis(espera), json);
    }

    static class ConClaveDeBrevo implements Condition {
        @Override
        public boolean matches(ConditionContext contexto, AnnotatedTypeMetadata metadatos) {
            return TransporteCorreo.seleccionar(contexto.getEnvironment()).equals("brevo");
        }
    }
}
