package com.tbridge.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.tbridge.auth.assembler.SesionModelAssembler;
import com.tbridge.auth.client.DeudasClient;
import com.tbridge.auth.config.CorreoConfig;
import com.tbridge.auth.controller.AuthController;
import com.tbridge.auth.controller.InternalController;
import com.tbridge.auth.dto.response.CodigoEmitidoResponse;
import com.tbridge.auth.model.MagicLink;
import com.tbridge.auth.repository.AccessCodeRepository;
import com.tbridge.auth.repository.AccessLogRepository;
import com.tbridge.auth.repository.MagicLinkRepository;
import com.tbridge.auth.repository.StaffUserRepository;
import com.tbridge.auth.security.JwtTestConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.mail.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ResendIntegrationTest {
    private static final String DESTINO = "recipient@fixture.local";
    private static final String RUT = "16482337-7";
    private static final String PORTAL = "http://portal.example.test";
    private final ObjectMapper json = new ObjectMapper();
    private final LinkedBlockingQueue<JsonNode> recibidos = new LinkedBlockingQueue<>();
    private final ResendDeliveryTelemetry telemetria = mock(ResendDeliveryTelemetry.class);
    private final UUID idProveedor = UUID.randomUUID();
    private final CountDownLatch responder = new CountDownLatch(1);
    private final LinkedBlockingQueue<Optional<String>> claves = new LinkedBlockingQueue<>();
    private volatile boolean retenerRespuesta;
    private HttpServer servidor;
    private final java.util.concurrent.atomic.AtomicInteger estado = new java.util.concurrent.atomic.AtomicInteger(200);

    @BeforeEach
    void iniciar() throws Exception {
        servidor = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        servidor.createContext("/emails", intercambio -> {
            try (intercambio) {
                if (!intercambio.getRequestMethod().equals("POST")
                        || !"Bearer fixture-only".equals(intercambio.getRequestHeaders().getFirst("Authorization"))
                        || !"application/json".equals(intercambio.getRequestHeaders().getFirst("Content-Type"))) {
                    intercambio.sendResponseHeaders(400, -1);
                    return;
                }
                claves.add(Optional.ofNullable(intercambio.getRequestHeaders().getFirst("Idempotency-Key")));
                recibidos.add(json.readTree(intercambio.getRequestBody()));
                if (retenerRespuesta) {
                    try {
                        if (!responder.await(5, TimeUnit.SECONDS)) return;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
                byte[] cuerpo = ("{\"id\":\"" + idProveedor + "\"}").getBytes(StandardCharsets.UTF_8);
                intercambio.sendResponseHeaders(estado.get(), cuerpo.length);
                intercambio.getResponseBody().write(cuerpo);
            }
        });
        servidor.start();
    }

    @AfterEach
    void detener() {
        responder.countDown();
        servidor.stop(0);
    }

    private ApplicationContextRunner contexto() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(MailSenderAutoConfiguration.class))
                .withUserConfiguration(CorreoConfig.class, MailService.class)
                .withBean(ObjectMapper.class, () -> json)
                .withBean(ResendDeliveryTelemetry.class, () -> telemetria)
                .withPropertyValues("app.mail.transport=resend", "app.mail.resend.api-key=fixture-only",
                        "app.mail.resend.url=http://127.0.0.1:" + servidor.getAddress().getPort() + "/emails",
                        "app.mail.brevo.api-key=fixture-brevo", "spring.mail.host=127.0.0.1",
                        "app.from-email=sender@example.com", "app.mail.reply-to=reply@example.com",
                        "app.public-url=" + PORTAL, "app.mail.solo-a=@fixture.local");
    }

    @Test
    void otp_recibido_por_resend_abre_sesion() {
        contexto().run(app -> {
            assertThat(app).hasSingleBean(JavaMailSender.class);
            var correo = app.getBean(MailService.class);
            assertThat(correo.comoSalen().por()).contains("Resend");
            var enlaces = mock(MagicLinkRepository.class);
            var codigos = mock(AccessCodeRepository.class);
            var deudas = mock(DeudasClient.class);
            var pedido = new AtomicReference<MagicLink>();
            when(enlaces.save(any())).thenAnswer(i -> {
                pedido.set(i.getArgument(0));
                return pedido.get();
            });
            when(enlaces.findFirstByDebtorRutAndCodeHashIsNotNullOrderByIssuedAtDescIdDesc(any()))
                    .thenAnswer(i -> Optional.ofNullable(pedido.get()));
            when(codigos.findFirstByDebtorRutOrderByIssuedAtDescIdDesc(any())).thenReturn(Optional.empty());
            when(deudas.correoDelDeudor(any())).thenReturn(Optional.of(DESTINO));
            var jwt = JwtTestConfig.service();
            var auth = new AuthService(codigos, enlaces, mock(StaffUserRepository.class),
                    mock(AccessLogRepository.class), jwt,
                    new SessionService(new SesionesEnMemoria().repositorio, Duration.ofDays(7), Clock.systemUTC()),
                    correo, deudas, PORTAL, 24, 15);
            var mvc = MockMvcBuilders.standaloneSetup(new AuthController(auth, jwt, new SesionModelAssembler(),
                    false, new IdentifierLimit(10, 60, 900))).build();
            mvc.perform(post("/api/auth/codigo").contentType("application/json")
                    .content(json.createObjectNode().put("rut", RUT).toString())).andExpect(status().isAccepted());

            JsonNode carta = cartaRecibida();
            assertThat(carta.path("subject").asText()).isEqualTo("Tu código para entrar a DataBridge");
            assertThat(carta.path("from").asText()).isEqualTo("DataBridge <sender@example.com>");
            assertThat(carta.path("to").get(0).asText()).isEqualTo(DESTINO);
            assertThat(carta.path("reply_to").get(0).asText()).isEqualTo("reply@example.com");
            var codigo = Pattern.compile("(?<![A-Z0-9])[2-9A-HJKMNP-Z]{6}(?![A-Z0-9])")
                    .matcher(carta.path("text").asText());
            assertThat(codigo.find()).isTrue();
            String otp = codigo.group();
            assertThat(carta.path("html").asText().contains(otp)).isTrue();
            assertThat(pedido.get().getCodeHash() != null && !pedido.get().getCodeHash().equals(otp)).isTrue();
            mvc.perform(post("/api/auth/acceso").contentType("application/json")
                    .content(json.createObjectNode().put("rut", RUT).put("codigo", otp).toString()))
                    .andExpect(status().isOk());
            assertThat(pedido.get().getConsumedAt()).isNotNull();
            verify(telemetria).aceptar(eq(idProveedor), any(Instant.class));
        });
    }

    @Test
    void contrato_interno_envia_invitacion_recordatorio_y_campana() {
        contexto().run(app -> {
            var auth = mock(AuthService.class);
            when(auth.emitirCodigo(any(), any(), any())).thenReturn(
                    new CodigoEmitidoResponse("K7M2QX", RUT, List.of("correo"),
                            Instant.now().plusSeconds(3600), "Portal"));
            var mvc = MockMvcBuilders.standaloneSetup(new InternalController(
                    auth, app.getBean(MailService.class), "fixture-internal-key")).build();
            for (String motivo : List.of("invitacion", "recordatorio", "campana")) {
                var cuerpo = json.createObjectNode().put("rut", RUT).put("correo", DESTINO)
                        .put("acreedor", "Empresa").put("paraQue", motivo);
                if (motivo.equals("recordatorio")) cuerpo.put("vence", "2026-11-10");
                mvc.perform(post("/internal/codigos").header("X-Internal-Key", "fixture-internal-key")
                        .contentType("application/json").content(cuerpo.toString())).andExpect(status().isOk());
                assertThat(cartaRecibida().path("to").get(0).asText()).isEqualTo(DESTINO);
            }
            assertThat(app.getBean(MailService.class).probar(DESTINO).enviado()).isTrue();
            cartaRecibida();
            verify(telemetria, times(4)).aceptar(eq(idProveedor), any(Instant.class));
            verify(auth, never()).anularCodigo(any(), any());
        });
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {400, 401, 403, 429, 503})
    void clasifica_rechazos_sin_reintentar_ni_usar_otro_proveedor(int http) {
        estado.set(http);
        contexto().run(app -> {
            var respuesta = app.getBean(MailService.class).probar(DESTINO);
            assertThat(respuesta.enviado()).isFalse();
            assertThat(respuesta.error()).contains("HTTP " + http).doesNotContain("fixture-only");
            cartaRecibida();
            assertThat(recibidos).isEmpty();
            verifyNoInteractions(telemetria);
        });
    }

    @Test
    void timeout_despues_de_recibir_el_post_deja_la_aceptacion_desconocida() {
        retenerRespuesta = true;
        contexto().withPropertyValues("app.mail.timeout-ms=1000").run(app -> {
            assertThat(app).hasSingleBean(JavaMailSender.class);
            var respuesta = app.getBean(MailService.class).probar(DESTINO);
            assertThat(respuesta.enviado()).isFalse();
            assertThat(respuesta.error()).contains("aceptación desconocida");
            cartaRecibida();
            responder.countDown();
            assertThat(claves).containsExactly(Optional.empty());
            verifyNoInteractions(telemetria);
        });
    }

    @Test
    void un_uuid_de_trabajo_estable_se_conserva_en_distintos_mensajes() {
        contexto().run(app -> {
            var sender = app.getBean(JavaMailSender.class);
            UUID trabajo = UUID.randomUUID();
            for (int intento = 0; intento < 2; intento++) {
                var mensaje = sender.createMimeMessage();
                mensaje.setFrom("sender@example.com");
                mensaje.setRecipients(jakarta.mail.Message.RecipientType.TO, DESTINO);
                mensaje.setSubject("Fixture");
                mensaje.setText("Contenido estable");
                mensaje.setHeader("X-DataBridge-Mail-Job-Id", trabajo.toString());
                sender.send(mensaje);
                cartaRecibida();
            }
            var clave = Optional.of("mail-job/" + trabajo);
            assertThat(claves).containsExactly(clave, clave);
        });
    }

    private JsonNode cartaRecibida() throws InterruptedException {
        JsonNode carta = recibidos.poll(5, TimeUnit.SECONDS);
        assertThat(carta != null).as("Resend simulado recibió un correo").isTrue();
        return carta;
    }
}
