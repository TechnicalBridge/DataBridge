package com.tbridge.debt.security;

import com.sun.net.httpserver.HttpServer;
import com.tbridge.debt.config.JwtConfig;
import com.nimbusds.jose.jwk.*;
import org.junit.jupiter.api.*;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.oauth2.server.resource.authentication.*;
import org.springframework.security.oauth2.jwt.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class JwksHttpTest {
    private HttpServer server;
    private ExecutorService executor;
    private final AtomicInteger requests=new AtomicInteger();
    private final AtomicInteger status=new AtomicInteger(200);
    private final AtomicReference<String> body=new AtomicReference<>();
    private final AtomicReference<CountDownLatch> gate=new AtomicReference<>();
    private String uri;

    private static String keys(ECKey... keys) { return new JWKSet(Arrays.asList(keys)).toString(); }
    @BeforeEach void start() throws Exception {
        body.set(keys(JwtTestTokens.publicKey(JwtTestTokens.PAIR)));
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        executor=Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/jwks",exchange -> {
            requests.incrementAndGet();
            try {
                var latch=gate.get();
                if(latch!=null) latch.await(3,TimeUnit.SECONDS);
                byte[] bytes=body.get().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(status.get(),bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch(InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            catch(java.io.IOException disconnected) { /* El cliente de timeout ya cerro. */ }
            finally { exchange.close(); }
        });
        server.start(); uri="http://127.0.0.1:"+server.getAddress().getPort()+"/jwks";
    }
    @AfterEach void close() {
        var latch=gate.get(); if(latch!=null) latch.countDown();
        server.stop(0); executor.shutdownNow();
    }
    @Test void initial_fetch_cache_and_known_key_during_short_outage() {
        var decoder=JwtConfig.remoteDecoder(uri,1000,2000);
        assertEquals(0,requests.get());
        assertNotNull(decoder.decode(JwtTestTokens.token()));
        int fetched=requests.get(); assertTrue(fetched>0);
        assertNotNull(decoder.decode(JwtTestTokens.token())); assertEquals(fetched,requests.get());
        server.stop(0);
        assertNotNull(decoder.decode(JwtTestTokens.token())); assertEquals(fetched,requests.get());
    }
    @Test void new_kid_refreshes_and_old_stays_valid() {
        var decoder=JwtConfig.remoteDecoder(uri,1000,2000); decoder.decode(JwtTestTokens.token());
        int fetched=requests.get();
        var next=JwtTestTokens.pair("secp256r1"); var nextPublic=JwtTestTokens.publicKey(next);
        body.set(keys(JwtTestTokens.publicKey(JwtTestTokens.PAIR),nextPublic));
        String nextToken=JwtTestTokens.token(JwtTestTokens.claims(),next,nextPublic.getKeyID(),"ES256");
        assertNotNull(decoder.decode(nextToken)); assertTrue(requests.get()>fetched);
        assertNotNull(decoder.decode(JwtTestTokens.token()));
        body.set(keys(nextPublic));
        var cold=JwtConfig.remoteDecoder(uri,1000,2000);
        assertThrows(BadJwtException.class,()->cold.decode(JwtTestTokens.token()));
        assertNotNull(cold.decode(nextToken));
    }
    @Test void unknown_kid_rejected_after_http_refresh() {
        var decoder=JwtConfig.remoteDecoder(uri,1000,2000); decoder.decode(JwtTestTokens.token());
        int fetched=requests.get();
        var token=JwtTestTokens.token(JwtTestTokens.claims(),JwtTestTokens.PAIR,"unknown","ES256");
        assertThrows(BadJwtException.class,()->decoder.decode(token)); assertTrue(requests.get()>fetched);
    }
    @Test void wrong_key_with_known_kid_rejected() {
        var other=JwtTestTokens.pair("secp256r1");
        var token=JwtTestTokens.token(JwtTestTokens.claims(),other,JwtTestTokens.publicKey(JwtTestTokens.PAIR).getKeyID(),"ES256");
        assertThrows(BadJwtException.class,()->JwtConfig.remoteDecoder(uri,1000,2000).decode(token));
    }
    @Test void unavailable_fails_closed_and_recovers() {
        status.set(500); var decoder=JwtConfig.remoteDecoder(uri,1000,2000);
        var provider=new JwtAuthenticationProvider(decoder);
        assertThrows(AuthenticationServiceException.class,
                ()->provider.authenticate(new BearerTokenAuthenticationToken(JwtTestTokens.token())));
        status.set(200); assertNotNull(decoder.decode(JwtTestTokens.token()));
    }
    @Test void malformed_jwks_fails_closed() {
        body.set("invalid"); assertThrows(JwtException.class,()->JwtConfig.remoteDecoder(uri,1000,2000).decode(JwtTestTokens.token()));
    }
    @Test void timeout_fails_closed_without_internet_or_sleep() {
        gate.set(new CountDownLatch(1));
        var decoder=JwtConfig.remoteDecoder(uri,1000,200);
        assertTimeout(DurationLimit.VALUE,()->assertThrows(JwtException.class,()->decoder.decode(JwtTestTokens.token())));
    }
    @Test void missing_kid_and_hmac_never_fetch() {
        var decoder=JwtConfig.remoteDecoder(uri,1000,2000);
        for(String alg:List.of("ES256","HS256","HS384","HS512","none")) {
            String token=JwtTestTokens.token(JwtTestTokens.claims(),JwtTestTokens.PAIR,null,alg);
            assertThrows(JwtException.class,()->decoder.decode(token));
        }
        assertEquals(0,requests.get());
    }
    private static final class DurationLimit { static final java.time.Duration VALUE=java.time.Duration.ofSeconds(4); }
}
