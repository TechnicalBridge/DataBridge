package com.tbridge.payments.controller;

import com.sun.net.httpserver.HttpServer;
import com.tbridge.payments.assembler.PaymentModelAssembler;
import com.tbridge.payments.client.*;
import com.tbridge.payments.config.SecurityConfig;
import com.tbridge.payments.model.Payment;
import com.tbridge.payments.repository.*;
import com.tbridge.payments.security.*;
import com.tbridge.payments.service.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Portal JWT -> servicio real -> cliente HTTP real -> contrato controlado de ms-debt. */
@WebMvcTest(PaymentController.class)
@Import({SecurityConfig.class, JwtTestConfig.class, PaymentModelAssembler.class, PaymentService.class, DebtClient.class})
@ActiveProfiles("test")
class CheckoutSecurityTest {
    private static final List<String> requests = new CopyOnWriteArrayList<>();
    private static final HttpServer server = startServer();

    private static HttpServer startServer() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                String uri = exchange.getRequestURI().toString();
                requests.add(uri);
                boolean key = "clave-interna-de-prueba".equals(exchange.getRequestHeaders().getFirst("X-Internal-Key"));
                boolean own = uri.startsWith("/internal/debts/42/checkout?debtorRut=16482337-7");
                int status = !key ? 401 : own ? 200 : 403;
                String json = own ? """
                        {"debtId":42,"creditorRut":"76418902-7","debtorRut":"16482337-7",
                         "currency":"CLP","amount":410000,"installmentId":12,"installmentIds":[12],
                         "capital":410000,"interes":0,"descuento":0}
                        """ : "{\"error\":\"No puedes ver esta deuda\"}";
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
            return server;
        } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.debt-url", () -> "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @AfterAll static void stopServer() { server.stop(0); }
    @BeforeEach void resetRequests() { requests.clear(); }

    @Autowired MockMvc mvc;
    @Autowired JwtTestIssuer jwt;
    @MockitoBean PaymentRepository payments;
    @MockitoBean PaymentEventRepository events;
    @MockitoBean DebtNotificationRepository notifications;
    @MockitoBean WebhookVerifier verifier;
    @MockitoBean UfService uf;
    @MockitoBean KhipuClient khipu;
    @MockitoBean FirmaDeKhipu firma;
    @MockitoBean WebpayClient webpay;
    @MockitoBean MercadoPagoClient mercado;

    private String token(String role) {
        return "Bearer " + jwt.issue("16482337-7", null, role, null, "16482337-7");
    }

    @Test
    void ajena_e_inexistente_con_cuotas_distintas_rechazan_sin_efectos() throws Exception {
        for (long id : new long[]{40, 41}) {
            for (long cuota : new long[]{12, 98, 999}) {
                mvc.perform(post("/api/payments/checkout").header("Authorization", token("DEBTOR"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"debtId\":" + id + ",\"installmentIds\":[" + cuota + "],\"gateway\":\"webpay\"}"))
                        .andExpect(status().isForbidden())
                        .andExpect(content().json("{\"error\":\"No puedes ver esta deuda\"}", true));
            }
        }
        assertEquals(6, requests.size());
        assertTrue(requests.stream().allMatch(uri -> uri.contains("/checkout?debtorRut=16482337-7&installmentIds=")));
        verifyNoInteractions(payments, events, notifications, uf, khipu, webpay, mercado);
    }

    @Test
    void solo_el_deudor_autenticado_puede_consultar_el_snapshot() throws Exception {
        String body = "{\"debtId\":42,\"gateway\":\"webpay\"}";
        mvc.perform(post("/api/payments/checkout").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnauthorized());
        for (String role : List.of("CREDITOR", "ADMIN")) {
            mvc.perform(post("/api/payments/checkout").header("Authorization", token(role))
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isForbidden());
        }
        assertTrue(requests.isEmpty());
        verifyNoInteractions(payments, events, webpay, khipu, mercado);
    }

    @Test
    void propia_crea_pago_con_monto_del_snapshot_y_rut_del_jwt() throws Exception {
        when(payments.save(any())).thenAnswer(call -> { Payment p = call.getArgument(0); p.setId(70L); return p; });
        mvc.perform(post("/api/payments/checkout").header("Authorization", token("DEBTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"debtId\":42,\"gateway\":\"webpay\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.amount").value(410000));
        assertEquals(List.of("/internal/debts/42/checkout?debtorRut=16482337-7"), requests);
        verify(payments).save(any());
        verify(events).save(any());
        verify(webpay, never()).createTransaction(any(), any(), anyLong(), any());
    }

    @Test
    void el_navegador_no_puede_enviar_identidad_ni_importe() throws Exception {
        mvc.perform(post("/api/payments/checkout").header("Authorization", token("DEBTOR"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"debtId\":42,\"gateway\":\"webpay\",\"debtorRut\":\"otro\",\"amount\":1}"))
                .andExpect(status().isBadRequest());
        assertTrue(requests.isEmpty());
        verifyNoInteractions(payments, events, webpay, khipu, mercado);
    }
}
