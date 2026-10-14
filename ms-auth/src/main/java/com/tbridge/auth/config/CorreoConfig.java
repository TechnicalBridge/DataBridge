package com.tbridge.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.mail.MailSender;
import org.springframework.util.StringUtils;

import java.time.Duration;

/**
 * Por donde salen los correos.
 *
 * <p>Con {@code BREVO_API_KEY}, por la API de Brevo ({@link CorreoPorBrevo}).
 * Sin ella, como siempre: Spring Boot arma el de SMTP si hay
 * {@code spring.mail.host}, y lo arma solo cuando no hay otro, asi que nunca
 * conviven los dos.
 */
@Configuration
public class CorreoConfig {

    @Bean
    @Conditional(ConClaveDeBrevo.class)
    MailSender correoPorBrevo(@Value("${app.mail.brevo.url}") String url,
                              @Value("${app.mail.brevo.api-key}") String clave,
                              @Value("${app.mail.timeout-ms:10000}") long espera,
                              ObjectMapper json) {
        return new CorreoPorBrevo(url, clave.strip(), Duration.ofMillis(espera), json);
    }

    /** Una clave vacia es lo mismo que no tenerla: es como queda el .env de quien no usa Brevo. */
    static class ConClaveDeBrevo implements Condition {
        @Override
        public boolean matches(ConditionContext contexto, AnnotatedTypeMetadata metadatos) {
            return StringUtils.hasText(contexto.getEnvironment().getProperty("app.mail.brevo.api-key"));
        }
    }
}
