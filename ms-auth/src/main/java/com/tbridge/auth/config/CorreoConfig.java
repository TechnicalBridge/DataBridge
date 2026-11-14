package com.tbridge.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.auth.service.ResendDeliveryTelemetry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.mail.javamail.JavaMailSender;

import java.time.Duration;

@Configuration
public class CorreoConfig {
    @Bean
    @Conditional(BrevoActivo.class)
    JavaMailSender correoPorBrevo(Environment env, ObjectMapper json) {
        return new CorreoPorBrevo(env.getRequiredProperty("app.mail.brevo.url"),
                clave(env, "brevo", "BREVO_API_KEY"), espera(env), json);
    }

    @Bean
    @Conditional(ResendActivo.class)
    JavaMailSender correoPorResend(Environment env, ObjectMapper json, ResendDeliveryTelemetry telemetria) {
        String clave = clave(env, "resend", "RESEND_API_KEY");
        return new CorreoPorResend(env.getProperty("app.mail.resend.url", "https://api.resend.com/emails"),
                clave, espera(env), json, telemetria);
    }

    private static String clave(Environment env, String proveedor, String variable) {
        String clave = env.getProperty("app.mail." + proveedor + ".api-key", "").strip();
        if (clave.isEmpty()) {
            throw new IllegalArgumentException("MAIL_TRANSPORT=" + proveedor + " requiere " + variable);
        }
        return clave;
    }

    private static Duration espera(Environment env) {
        return Duration.ofMillis(env.getProperty("app.mail.timeout-ms", Long.class, 10000L));
    }

    static class BrevoActivo implements Condition {
        @Override
        public boolean matches(ConditionContext contexto, AnnotatedTypeMetadata metadatos) {
            return TransporteCorreo.seleccionar(contexto.getEnvironment()).equals("brevo");
        }
    }

    static class ResendActivo implements Condition {
        @Override
        public boolean matches(ConditionContext contexto, AnnotatedTypeMetadata metadatos) {
            return TransporteCorreo.seleccionar(contexto.getEnvironment()).equals("resend");
        }
    }
}
