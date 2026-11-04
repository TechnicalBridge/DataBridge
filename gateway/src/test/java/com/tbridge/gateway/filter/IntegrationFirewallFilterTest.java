package com.tbridge.gateway.filter;

import org.junit.jupiter.api.Test;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.http.HttpStatus;
import reactor.core.publisher.Mono;
import java.net.InetSocketAddress;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class IntegrationFirewallFilterTest {
    private final String key = UUID.randomUUID().toString();
    private IntegrationFirewallFilter policy(String cidrs) {
        return new IntegrationFirewallFilter(IntegrationFirewallFilter.hash(key)+"="+cidrs,"127\\.0\\.0\\.1");
    }
    private HttpStatus request(IntegrationFirewallFilter filter, String path, String peer, String forwarded, String token) {
        var request = MockServerHttpRequest.get(path).remoteAddress(new InetSocketAddress(peer,1234))
                .header("Authorization","Bearer "+token).header("X-Forwarded-For",forwarded);
        var exchange = MockServerWebExchange.from(request);
        filter.filter(exchange, ex -> { ex.getResponse().setStatusCode(HttpStatus.ACCEPTED); return Mono.empty(); }).block();
        return (HttpStatus) exchange.getResponse().getStatusCode();
    }
    @Test void allowlistPorClaveIpv4Ipv6YProxyConfiable() {
        var filter = policy("192.0.2.0/24,2001:db8::/32");
        assertEquals(HttpStatus.ACCEPTED,request(filter,"/api/v1/carteras","192.0.2.1","203.0.113.1",key));
        assertEquals(HttpStatus.FORBIDDEN,request(filter,"/api/v1/carteras","203.0.113.1","192.0.2.1",key));
        assertEquals(HttpStatus.ACCEPTED,request(filter,"/api/v1/carteras","127.0.0.1","2001:db8::1",key));
        assertEquals(HttpStatus.FORBIDDEN,request(filter,"/api/v1/carteras","127.0.0.1","invalid",key));
        assertEquals(HttpStatus.ACCEPTED,request(filter,"/api/v1/carteras","203.0.113.1","",UUID.randomUUID().toString()));
    }
    @Test void noCambiaPortalWebhooksInternosNiDefault() {
        var filter = policy("192.0.2.0/24");
        for (String path : new String[]{"/api/debts","/api/auth/refresh","/internal/uf","/api/payments/webhooks/khipu"})
            assertEquals(HttpStatus.ACCEPTED,request(filter,path,"203.0.113.1","",key));
        assertEquals(HttpStatus.ACCEPTED,request(new IntegrationFirewallFilter("",""),"/api/v1/carteras","203.0.113.1","",key));
    }
    @Test void invalidoFallaSinIncluirConfiguracionEnMensaje() {
        for (String cidr : new String[]{"host.invalid/24","192.0.2.1/33","2001:db8::/129","","192.0.2.1"})
            assertThrows(IllegalArgumentException.class,()->policy(cidr));
        assertThrows(IllegalArgumentException.class,()->new IntegrationFirewallFilter("secret=192.0.2.0/24",""));
    }
    @Test void optionsNoSeBloquea() {
        var exchange = MockServerWebExchange.from(MockServerHttpRequest.options("/api/v1/carteras")
                .remoteAddress(new InetSocketAddress("203.0.113.1",1234)).header("Authorization","Bearer "+key));
        policy("192.0.2.0/24").filter(exchange, ex -> { ex.getResponse().setStatusCode(HttpStatus.NO_CONTENT); return Mono.empty(); }).block();
        assertEquals(HttpStatus.NO_CONTENT,exchange.getResponse().getStatusCode());
    }
}
