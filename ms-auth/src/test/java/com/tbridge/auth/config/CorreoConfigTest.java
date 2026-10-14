package com.tbridge.auth.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.MailSender;
import org.springframework.mail.javamail.JavaMailSenderImpl;

import static org.assertj.core.api.Assertions.assertThat;

/** Con la clave de Brevo, el correo sale por Brevo; sin ella, como siempre. Nunca los dos. */
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
                    assertThat(app).hasSingleBean(MailSender.class);
                    assertThat(app.getBean(MailSender.class)).isInstanceOf(CorreoPorBrevo.class);
                });
    }

    @Test
    void sin_la_clave_sale_por_smtp_como_siempre() {
        contexto.withPropertyValues("app.mail.brevo.api-key=", "spring.mail.host=smtp.gmail.com")
                .run(app -> {
                    assertThat(app).hasSingleBean(MailSender.class);
                    assertThat(app.getBean(MailSender.class)).isInstanceOf(JavaMailSenderImpl.class);
                });
    }

    @Test
    void sin_clave_ni_servidor_no_hay_por_donde_mandar() {
        contexto.withPropertyValues("app.mail.brevo.api-key=   ")
                .run(app -> assertThat(app).doesNotHaveBean(MailSender.class));
    }
}
