package com.tbridge.gateway.filter;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import java.net.*;
import java.util.UUID;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class DomainFirewallHttpTest {
    static final String key = UUID.randomUUID().toString();
    static final HttpServer upstream = start();
    @Autowired WebTestClient http;
    static HttpServer start() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
            server.createContext("/", exchange -> {
                byte[] body = "{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
                String auth = exchange.getRequestHeaders().getFirst("Authorization");
                if (auth != null) exchange.getResponseHeaders().set("X-Test-Authorization", auth);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(401,body.length);
                exchange.getResponseBody().write(body); exchange.close();
            }); server.start(); return server;
        } catch(Exception ex) { throw new IllegalStateException(ex); }
    }
    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        for (String service : new String[]{"auth","debt","payments"})
            registry.add("app.servicios."+service,()->"http://127.0.0.1:"+upstream.getAddress().getPort());
        registry.add("ratelimit.auth-capacity",()->2);
        registry.add("spring.cloud.gateway.server.webflux.trusted-proxies",()->"^$");
        registry.add("firewall.debt.integration-cidrs",()->IntegrationFirewallFilter.hash(key)+"=192.0.2.0/24");
    }
    @Test void politicasPorDominioPreservanHeadersYStatus() {
        for (int i=0; i<2;i++) http.post().uri("/api/auth/acceso").bodyValue("{}").exchange().expectStatus().isUnauthorized();
        http.post().uri("/api/auth/acceso").bodyValue("{}").exchange().expectStatus().isEqualTo(429);
        http.get().uri("/api/v1/cuenta").header("Authorization","Bearer "+key).header("X-Forwarded-For","192.0.2.1").exchange().expectStatus().isForbidden();
        for (String path : new String[]{"/api/debts","/api/payments/webhooks/khipu","/api/auth/refresh"})
            http.post().uri(path).header("Authorization","Bearer "+key).bodyValue("{}").exchange()
                    .expectStatus().isUnauthorized().expectHeader().valueEquals("X-Test-Authorization","Bearer "+key);
    }
    @AfterAll static void close() { upstream.stop(0); }
}
