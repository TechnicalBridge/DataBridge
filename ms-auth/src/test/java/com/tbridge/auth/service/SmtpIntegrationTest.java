package com.tbridge.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;
import com.tbridge.auth.config.BuzonDePrueba;
import com.tbridge.auth.config.CorreoConfig;
import com.tbridge.auth.client.DeudasClient;
import com.tbridge.auth.model.MagicLink;
import com.tbridge.auth.repository.*;
import com.tbridge.auth.controller.AuthController;
import com.tbridge.auth.assembler.SesionModelAssembler;
import com.tbridge.auth.security.JwtService;
import com.tbridge.auth.security.JwtTestConfig;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.LocalDate;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SMTP real en loopback, captura GreenMail; no hay entrega a Internet ni DB externa. */
class SmtpIntegrationTest {
    private ApplicationContextRunner contexto(GreenMail smtp) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
                .withUserConfiguration(CorreoConfig.class, MailService.class)
                .withBean(ObjectMapper.class, ObjectMapper::new)
                .withPropertyValues("app.mail.transport=smtp", "app.mail.brevo.api-key=",
                        "app.mail.brevo.url=http://127.0.0.1:1/never", "spring.mail.host=127.0.0.1",
                        "spring.mail.port=" + smtp.getSmtp().getPort(), "spring.mail.properties.mail.smtp.auth=false",
                        "spring.mail.properties.mail.smtp.starttls.enable=false",
                        "spring.mail.properties.mail.smtp.connectiontimeout=3000",
                        "spring.mail.properties.mail.smtp.timeout=3000", "spring.mail.properties.mail.smtp.writetimeout=3000",
                        "app.from-email=sender@example.com", "app.public-url=http://portal.example.test",
                        "app.mail.solo-a=@fixture.local")
                .withInitializer(app -> new BuzonDePrueba().postProcessEnvironment(app.getEnvironment(), null));
    }

    @Test
    void codigo_invitacion_recordatorio_y_prueba_llegan_sin_brevo() throws Exception {
        GreenMail smtp = new GreenMail(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
        smtp.start();
        try {
            contexto(smtp).run(app -> {
                assertThat(app).hasNotFailed();
                MailService correo = app.getBean(MailService.class);
                long inicio = System.nanoTime();
                correo.enviarCodigo("recipient@fixture.local", "K7M2QX", "Empresa de prueba");
                correo.enviarInvitacion("recipient@fixture.local", "Persona", "Portal", "http://portal.example.test", 24, "Portal");
                correo.enviarRecordatorio("recipient@fixture.local", "K7M2QX", "Empresa de prueba", LocalDate.of(2026, 11, 10));
                correo.probar("recipient@fixture.local");
                assertThat(smtp.waitForIncomingEmail(5000, 4)).isTrue();
                assertThat((System.nanoTime() - inicio) / 1_000_000).isLessThan(15000);
                MimeMessage[] cartas = smtp.getReceivedMessages();
                assertThat(cartas).hasSize(4);
                assertThat(cartas[0].getFrom()[0].toString()).contains("sender@example.com");
                assertThat(cartas[0].getAllRecipients()[0].toString()).isEqualTo("recipient@fixture.local");
                assertThat(cartas[0].getSubject()).isEqualTo("Tu código de acceso");
                assertThat(com.icegreen.greenmail.util.GreenMailUtil.getBody(cartas[0])).contains("K7M2QX");
                assertThat(new ReglasService(24, 15, 24, 168, 15, correo).reglas().toString())
                        .contains("SMTP 127.0.0.1:").doesNotContain("Brevo (API)");
            });
        } finally { smtp.stop(); }
    }

    @Test
    void post_codigo_recibe_otp_por_smtp_y_abre_sesion() throws Exception {
        GreenMail smtp = new GreenMail(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
        smtp.start();
        try {
            contexto(smtp).run(app -> {
                MagicLinkRepository enlaces = mock(MagicLinkRepository.class);
                AccessCodeRepository codigos = mock(AccessCodeRepository.class);
                DeudasClient deudas = mock(DeudasClient.class);
                AtomicReference<MagicLink> pedido = new AtomicReference<>();
                when(enlaces.save(any())).thenAnswer(i -> { pedido.set(i.getArgument(0)); return pedido.get(); });
                when(enlaces.findFirstByDebtorRutAndCodeHashIsNotNullOrderByIssuedAtDescIdDesc(any()))
                        .thenAnswer(i -> Optional.ofNullable(pedido.get()));
                when(codigos.findFirstByDebtorRutOrderByIssuedAtDescIdDesc(any())).thenReturn(Optional.empty());
                when(deudas.correoDelDeudor(any())).thenReturn(Optional.of("recipient@fixture.local"));
                JwtService jwt = JwtTestConfig.service();
                AuthService auth = new AuthService(codigos, enlaces, mock(StaffUserRepository.class),
                        mock(AccessLogRepository.class), jwt,
                        new SessionService(new SesionesEnMemoria().repositorio, java.time.Duration.ofDays(7), java.time.Clock.systemUTC()),
                        app.getBean(MailService.class), deudas, "http://portal.example.test", 24, 15);
                var mvc = MockMvcBuilders.standaloneSetup(new AuthController(auth, jwt, new SesionModelAssembler(),
                        false, new IdentifierLimit(10, 60, 900))).build();
                mvc.perform(post("/api/auth/codigo").contentType("application/json")
                        .content("{\"rut\":\"16482337-7\"}")).andExpect(status().isAccepted());
                assertThat(smtp.waitForIncomingEmail(5000, 1)).isTrue();
                String texto = com.icegreen.greenmail.util.GreenMailUtil.getBody(smtp.getReceivedMessages()[0]);
                var codigo = Pattern.compile("(?<![A-Z0-9])[2-9A-HJKMNP-Z]{6}(?![A-Z0-9])").matcher(texto);
                assertThat(codigo.find()).isTrue();
                mvc.perform(post("/api/auth/acceso").contentType("application/json")
                        .content("{\"rut\":\"16482337-7\",\"codigo\":\"" + codigo.group() + "\"}"))
                        .andExpect(status().isOk());
                assertThat(pedido.get().getConsumedAt()).isNotNull();
            });
        } finally { smtp.stop(); }
    }
    @Test
    void contrato_interno_de_invitacion_recordatorio_y_campana_envia_por_smtp() throws Exception {
        GreenMail smtp = new GreenMail(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
        smtp.start();
        try {
            contexto(smtp).run(app -> {
                AuthService auth = mock(AuthService.class);
                when(auth.emitirCodigo(any(), any(), any())).thenReturn(
                        new com.tbridge.auth.dto.response.CodigoEmitidoResponse("K7M2QX", "16482337-7",
                                java.util.List.of("correo"), java.time.Instant.now().plusSeconds(3600), "Portal"));
                var mvc = MockMvcBuilders.standaloneSetup(new com.tbridge.auth.controller.InternalController(
                        auth, app.getBean(MailService.class), "fixture-internal-key")).build();
                for (String motivo : new String[]{"invitacion", "recordatorio", "campana"}) {
                    String fecha = motivo.equals("recordatorio") ? ",\"vence\":\"2026-11-10\"" : "";
                    mvc.perform(post("/internal/codigos").header("X-Internal-Key", "fixture-internal-key")
                            .contentType("application/json").content("{\"rut\":\"16482337-7\","
                                    + "\"correo\":\"recipient@fixture.local\",\"acreedor\":\"Empresa\","
                                    + "\"paraQue\":\"" + motivo + "\"" + fecha + "}"))
                            .andExpect(status().isOk());
                }
                assertThat(smtp.waitForIncomingEmail(5000, 3)).isTrue();
                assertThat(smtp.getReceivedMessages()).hasSize(3);
                verify(auth, never()).anularCodigo(any(), any());
            });
        } finally { smtp.stop(); }
    }

    @Test
    void mailpit_seleccionado_por_entorno_crea_un_unico_sender() {
        GreenMail smtp = new GreenMail(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
        smtp.start();
        try {
            contexto(smtp).withPropertyValues("app.mail.transport=mailpit", "app.mail.solo-a=",
                    "app.mail.buzon-de-prueba=127.0.0.1:" + smtp.getSmtp().getPort(),
                    "app.mail.brevo.api-key=fixture-only", "spring.mail.properties.mail.smtp.auth=true",
                    "spring.mail.properties.mail.smtp.starttls.required=true").run(app -> {
                assertThat(app).hasSingleBean(org.springframework.mail.javamail.JavaMailSender.class);
                app.getBean(MailService.class).enviarCodigo("recipient@example.com", "K7M2QX", "Empresa");
                assertThat(smtp.waitForIncomingEmail(5000, 1)).isTrue();
                assertThat(app.getBean(MailService.class).comoSalen().por()).contains("buzón de prueba");
            });
        } finally { smtp.stop(); }
    }    @Test
    void smtp_autenticado_y_starttls_obligatorio_no_degrada_a_texto_claro() {
        GreenMail smtp = new GreenMail(new ServerSetup(0, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
        smtp.setUser("recipient@fixture.local", "fixture-user", "fixture-password");
        smtp.start();
        try {
            contexto(smtp).withPropertyValues("spring.mail.properties.mail.smtp.auth=true",
                    "spring.mail.username=fixture-user", "spring.mail.password=fixture-password").run(app -> {
                assertThat(app).hasNotFailed();
                assertThat(app.getBean(MailService.class).probar("recipient@fixture.local").enviado()).isTrue();
                assertThat(smtp.waitForIncomingEmail(5000, 1)).isTrue();
            });
            smtp.purgeEmailFromAllMailboxes();
            contexto(smtp).withPropertyValues("spring.mail.properties.mail.smtp.starttls.enable=true",
                    "spring.mail.properties.mail.smtp.starttls.required=true").run(app -> {
                assertThat(app).hasNotFailed();
                assertThat(app.getBean(MailService.class).probar("recipient@fixture.local").enviado()).isFalse();
                assertThat(smtp.getReceivedMessages()).isEmpty();
            });
        } catch (Exception e) { throw new AssertionError(e); }
        finally { smtp.stop(); }
    }}
