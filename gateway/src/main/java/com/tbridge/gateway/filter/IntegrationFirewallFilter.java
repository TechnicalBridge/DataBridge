package com.tbridge.gateway.filter;

import io.netty.handler.ipfilter.IpFilterRuleType;
import io.netty.handler.ipfilter.IpSubnetFilterRule;
import io.netty.util.NetUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** Restricción opcional por huella de API key; la autenticación sigue en ms-debt. */
@Component
public class IntegrationFirewallFilter implements GlobalFilter, Ordered {
    private final Map<String, List<IpSubnetFilterRule>> rules = new HashMap<>();
    private final ClienteReal clients;

    public IntegrationFirewallFilter(@Value("${firewall.debt.integration-cidrs:}") String config,
            @Value("${spring.cloud.gateway.server.webflux.trusted-proxies:127\\.0\\.0\\.1}") String proxies) {
        clients = new ClienteReal(proxies);
        if (config.isBlank()) return;
        for (String entry : config.split(";", -1)) {
            String[] pair = entry.trim().split("=", -1);
            if (pair.length != 2 || !pair[0].matches("[a-f0-9]{64}") || rules.containsKey(pair[0])) invalid();
            List<IpSubnetFilterRule> cidrs = new ArrayList<>();
            for (String cidr : pair[1].split(",", -1)) {
                String[] parts = cidr.trim().split("/", -1);
                if (parts.length != 2 || !(NetUtil.isValidIpV4Address(parts[0]) || NetUtil.isValidIpV6Address(parts[0]))) invalid();
                try {
                    int bits = Integer.parseInt(parts[1]);
                    int max = NetUtil.isValidIpV4Address(parts[0]) ? 32 : 128;
                    if (bits < 0 || bits > max) invalid();
                    cidrs.add(new IpSubnetFilterRule(parts[0], bits, IpFilterRuleType.ACCEPT));
                } catch (Exception ex) { throw new IllegalArgumentException("Política CIDR de integración inválida"); }
            }
            rules.put(pair[0], List.copyOf(cidrs));
        }
    }

    private static void invalid() { throw new IllegalArgumentException("Política CIDR de integración inválida"); }

    @Override public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        var request = exchange.getRequest();
        if (!request.getPath().value().startsWith("/api/v1/") || "OPTIONS".equals(request.getMethod().name()) || rules.isEmpty()) return chain.filter(exchange);
        String authorization = request.getHeaders().getFirst("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) return chain.filter(exchange);
        List<IpSubnetFilterRule> allowed = rules.get(hash(authorization.substring(7).trim()));
        if (allowed == null) return chain.filter(exchange);
        String ip = clients.de(request);
        boolean permitted = (NetUtil.isValidIpV4Address(ip) || NetUtil.isValidIpV6Address(ip))
                && allowed.stream().anyMatch(rule -> rule.matches(new InetSocketAddress(ip, 0)));
        if (permitted) return chain.filter(exchange);
        org.slf4j.LoggerFactory.getLogger(getClass()).info("firewall domain=DEBT rule=INTEGRATION_CIDR outcome=denied status=403");
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        return exchange.getResponse().setComplete();
    }

    static String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }
    @Override public int getOrder() { return -3; }
}
