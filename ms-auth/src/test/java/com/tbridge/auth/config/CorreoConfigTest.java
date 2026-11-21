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
    void con_la_clave_de_brevo_sale_por_brevo_aunque_haya_un_servidor_smtp() {
        contexto.withPropertyValues("app.mail.brevo.api-key=xkeysib-de-prueba", "spring.mail.host=smtp.gmail.com")
                .run(app -> {
                    assertThat(app).hasSingleBean(JavaMailSender.class);
                    assertThat(app.getBean(JavaMailSender.class)).isInstanceOf(CorreoPorBrevo.class);
                });
    }

    @Test
    void sin_la_clave_sale_por_smtp_como_siempre() {
        contexto.withPropertyValues("app.mail.brevo.api-key=", "spring.mail.host=smtp.gmail.com")
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
    void resend_sin_clave_falla_aunque_brevo_y_smtp_esten_configurados() {
        contexto.withBean(com.tbridge.auth.service.ResendDeliveryTelemetry.class,
                        () -> org.mockito.Mockito.mock(com.tbridge.auth.service.ResendDeliveryTelemetry.class))
                .withPropertyValues("app.mail.transport=resend", "app.mail.resend.api-key= ",
                        "app.mail.brevo.api-key=fixture-only", "spring.mail.host=smtp.example.com")
                .run(app -> {
                    assertThat(app).hasFailed();
                    assertThat(app.getStartupFailure()).hasRootCauseMessage("MAIL_TRANSPORT=resend requiere RESEND_API_KEY");
                });
    }

    @Test
    void smtp_explicito_prevalece_sobre_brevo() {
        contexto.withPropertyValues("app.mail.transport=smtp", "app.mail.brevo.api-key=fixture-only",
                        "spring.mail.host=smtp.example.com")
                .run(app -> {
                    assertThat(app).hasSingleBean(JavaMailSender.class);
                    assertThat(app.getBean(JavaMailSender.class)).isExactlyInstanceOf(JavaMailSenderImpl.class);
                });
    }

    @Test
    void brevo_explicito_prevalece_sobre_smtp() {
        contexto.withPropertyValues("app.mail.transport=brevo", "app.mail.brevo.api-key=fixture-only",
                        "spring.mail.host=smtp.example.com")
                .run(app -> {
                    assertThat(app).hasSingleBean(JavaMailSender.class);
                    assertThat(app.getBean(JavaMailSender.class)).isInstanceOf(CorreoPorBrevo.class);
                });
    }

    @Test
    void mailpit_explicito_prevalece_sobre_brevo_y_smtp() {
        contexto.withInitializer(app -> new BuzonDePrueba().postProcessEnvironment(app.getEnvironment(), null))
                .withPropertyValues("app.mail.transport=mailpit", "app.mail.brevo.api-key=fixture-only",
                        "spring.mail.host=smtp.example.com", "app.mail.buzon-de-prueba=mailpit:1025")
                .run(app -> {
                    assertThat(app).hasSingleBean(JavaMailSender.class);
                    var sender = (JavaMailSenderImpl) app.getBean(JavaMailSender.class);
                    assertThat(sender.getHost()).isEqualTo("mailpit");
                    assertThat(sender.getPort()).isEqualTo(1025);
                });
    }
}
