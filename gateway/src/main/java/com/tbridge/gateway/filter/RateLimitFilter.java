package com.tbridge.gateway.filter;

import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RateLimitFilter implements GlobalFilter, Ordered {

    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();
    private final long authCapacity;
    private final long globalCapacity;
    private final Duration authRefill;
    private final Duration globalRefill;
    private final ClienteReal clientes;

    public RateLimitFilter(
            @Value("${ratelimit.auth-capacity:10}") long authCapacity,
            @Value("${ratelimit.auth-refill-minutes:1}") long authRefillMinutes,
            @Value("${ratelimit.global-capacity:120}") long globalCapacity,
            @Value("${ratelimit.global-refill-minutes:1}") long globalRefillMinutes,
            //  La misma definicion de "en quien se confia" que usa Spring Cloud
            //  Gateway para reenviar X-Forwarded-For a los servicios.
            @Value("${spring.cloud.gateway.server.webflux.trusted-proxies:127\\.0\\.0\\.1}") String confiables
    ) {
        this.authCapacity = authCapacity;
        this.globalCapacity = globalCapacity;
        this.authRefill = Duration.ofMinutes(Math.max(1, authRefillMinutes));
        this.globalRefill = Duration.ofMinutes(Math.max(1, globalRefillMinutes));
        this.clientes = new ClienteReal(confiables);
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        if (request.getMethod() != null && "OPTIONS".equalsIgnoreCase(request.getMethod().name())) {
            return chain.filter(exchange);
        }
        String path = request.getURI().getPath();
        //  El cupo estrecho protege secretos que se pueden adivinar —el codigo
        //  de seis caracteres— y el envio de correos. Renovar y cerrar sesion
        //  no son eso: la llave tiene 256 bits al azar. Contarlos aca hacia que
        //  recargar la pagina diez veces en un minuto dejara a alguien afuera,
        //  porque cada recarga renueva la sesion. Van al cupo general.
        boolean authPath = path.startsWith("/api/auth/")
                && !path.equals("/api/auth/refresh") && !path.equals("/api/auth/logout");
        String ip = clientes.de(request);
        String key = (authPath ? "auth:" : "api:") + ip;
        Bucket bucket = buckets.computeIfAbsent(key, k -> authPath ? authBucket() : globalBucket());
        if (bucket.tryConsume(1)) {
            return chain.filter(exchange);
        }
        exchange.getResponse().setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
        org.slf4j.LoggerFactory.getLogger(getClass()).info("firewall domain={} rule=IP_RATE_LIMIT outcome=denied status=429",
                authPath ? "AUTH" : path.startsWith("/api/payments/") ? "PAYMENTS" : path.startsWith("/api/v1/") || path.startsWith("/api/debts/") ? "DEBT" : "API");
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = "{\"error\":\"Demasiadas solicitudes. Intenta en un minuto.\"}"
                .getBytes(StandardCharsets.UTF_8);
        return exchange.getResponse().writeWith(Mono.just(exchange.getResponse().bufferFactory().wrap(body)));
    }

    private Bucket authBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(authCapacity)
                .refillGreedy(authCapacity, authRefill)
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    private Bucket globalBucket() {
        Bandwidth limit = Bandwidth.builder()
                .capacity(globalCapacity)
                .refillGreedy(globalCapacity, globalRefill)
                .build();
        return Bucket.builder().addLimit(limit).build();
    }

    @Override
    public int getOrder() {
        return -2;
    }
}
