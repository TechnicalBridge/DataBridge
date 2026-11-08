package com.tbridge.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Con la clave de Brevo, el correo sale por Brevo; sin ella, como siempre. Nunca los dos, y siempre un
 * {@link JavaMailSender}: es lo que necesita MailService para armar el correo con su texto y su HTML.
 */
class CorreoConfigTest {

    private final ApplicationContextRunner contexto = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
            .withUserConfiguration(CorreoConfig.class)
            .withBean(ObjectMapper.class, ObjectMapper::new)
            .withPropertyValues("app.mail.brevo.url=https://api.brevo.com/v3/smtp/email");

    @Test
    void brevo_explicito_tiene_prioridad_sobre_smtp() {
        contexto.withPropertyValues("app.mail.transport=brevo", "app.mail.brevo.api-key=xkeysib-de-prueba", "spring.mail.host=smtp.example.test")
                .run(app -> {
                    assertThat(app).hasSingleBean(JavaMailSender.class);
                    assertThat(app.getBean(JavaMailSender.class)).isInstanceOf(CorreoPorBrevo.class);
                });
    }

    @Test
    void sin_la_clave_sale_por_smtp_como_siempre() {
        contexto.withPropertyValues("app.mail.brevo.api-key=", "spring.mail.host=smtp.example.test")
                .run(app -> {
                    assertThat(app).hasSingleBean(JavaMailSender.class);
                    assertThat(app.getBean(JavaMailSender.class)).isInstanceOf(JavaMailSenderImpl.class);
                });
    }

    @Test
    void sin_clave_ni_servidor_no_hay_por_donde_mandar() {
        contexto.withPropertyValues("app.mail.brevo.api-key=   ")
                .run(app -> assertThat(app).doesNotHaveBean(JavaMailSender.class));
    }
    @Test
    void smtp_explicito_y_automatico_ignoran_una_clave_antigua_de_brevo() {
        for (String modo : new String[]{"", "smtp"}) {
            contexto.withPropertyValues("app.mail.transport=" + modo, "spring.mail.host=smtp.example.test",
                    "app.mail.brevo.api-key=fixture-only").run(app -> {
                assertThat(app).hasSingleBean(JavaMailSender.class);
                assertThat(app.getBean(JavaMailSender.class)).isInstanceOf(JavaMailSenderImpl.class);
            });
        }
    }

    @Test
    void credenciales_tls_y_timeouts_se_conservan_en_spring_mail() {
        for (boolean ssl : new boolean[]{false, true}) {
            contexto.withPropertyValues("app.mail.transport=smtp", "spring.mail.host=smtp.example.test",
                    "spring.mail.port=" + (ssl ? 465 : 587), "spring.mail.username=fixture",
                    "spring.mail.password=fixture-only", "spring.mail.properties.mail.smtp.auth=true",
                    "spring.mail.properties.mail.smtp.ssl.enable=" + ssl,
                    "spring.mail.properties.mail.smtp.starttls.enable=" + !ssl,
                    "spring.mail.properties.mail.smtp.connectiontimeout=2000",
                    "spring.mail.properties.mail.smtp.timeout=2000",
                    "spring.mail.properties.mail.smtp.writetimeout=2000").run(app -> {
                assertThat(app).hasNotFailed();
                JavaMailSenderImpl sender = app.getBean(JavaMailSenderImpl.class);
                assertThat(sender.getUsername()).isEqualTo("fixture");
                assertThat(sender.getPassword()).isEqualTo("fixture-only");
                assertThat(sender.getJavaMailProperties()).containsEntry("mail.smtp.ssl.enable", "" + ssl)
                        .containsEntry("mail.smtp.starttls.enable", "" + !ssl)
                        .containsEntry("mail.smtp.connectiontimeout", "2000")
                        .containsEntry("mail.smtp.timeout", "2000").containsEntry("mail.smtp.writetimeout", "2000");
            });
        }
    }

    @Test
    void configuracion_invalida_falla_sin_exponer_secretos() {
        for (String[] propiedades : new String[][]{
                {"app.mail.transport=smtp"}, {"app.mail.transport=brevo"}, {"app.mail.transport=desconocido"},
                {"spring.mail.host=smtp.example.test", "spring.mail.properties.mail.smtp.auth=true"},
                {"spring.mail.host=smtp.example.test", "spring.mail.properties.mail.smtp.ssl.enable=true",
                        "spring.mail.properties.mail.smtp.starttls.enable=true"},
                {"spring.mail.host=smtp.example.test", "spring.mail.properties.mail.smtp.timeout=0"}}) {
            contexto.withPropertyValues(propiedades).run(app -> assertThat(app).hasFailed());
        }
    }}
