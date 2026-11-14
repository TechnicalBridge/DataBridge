package com.tbridge.auth.config;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;
import org.springframework.mail.*;
import static org.junit.jupiter.api.Assertions.*;

class BrevoRetryTest {
    @ParameterizedTest @ValueSource(ints={429,500,502,503,504})
    void temporary_response_retries_then_accepts(int status) throws Exception { run(status,false,2); }
    @ParameterizedTest @ValueSource(ints={400,401,402,403,404,422})
    void permanent_response_never_retries(int status) throws Exception { run(status,true,1); }
    @Test void repeated_503_stops_at_three_attempts() throws Exception { run(503,true,3); }
    private void run(int status,boolean alwaysFail,int expected) throws Exception {
        AtomicInteger calls=new AtomicInteger();
        List<Long> sleeps=new ArrayList<>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            int n=calls.incrementAndGet();
            byte[] body="{}".getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(alwaysFail||n==1?status:201,body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });server.start();
        try {
            var sender=new CorreoPorBrevo("http://127.0.0.1:"+server.getAddress().getPort(),"fake-test-key",
                    Duration.ofMillis(500),new ObjectMapper(),sleeps::add, bound -> bound,(id,at)->{});
            var message=new SimpleMailMessage();message.setFrom("from@example.com");message.setTo("to@example.com");message.setText("fixture");
            if(alwaysFail) assertThrows(MailException.class,()->sender.send(message)); else sender.send(message);
            assertEquals(expected,calls.get());
            assertEquals(expected-1,sleeps.size());
            if(!sleeps.isEmpty()) assertEquals(500L,sleeps.getFirst());
            if(sleeps.size()==2) assertEquals(1000L,sleeps.getLast());
        } finally { server.stop(0); }
    }
    @Test void respects_retry_after_without_long_sleep() throws Exception {
        AtomicInteger calls=new AtomicInteger();List<Long> sleeps=new ArrayList<>();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",e->{e.getRequestBody().readAllBytes(); int n=calls.incrementAndGet();
            if(n==1)e.getResponseHeaders().set("Retry-After","2");e.sendResponseHeaders(n==1?429:201,-1);e.close();});server.start();
        try {
            var sender=new CorreoPorBrevo("http://127.0.0.1:"+server.getAddress().getPort(),"fake-test-key",Duration.ofSeconds(1),
                    new ObjectMapper(),sleeps::add,b->0,(id,at)->{});
            var m=new SimpleMailMessage();m.setFrom("from@example.com");m.setTo("to@example.com");sender.send(m);
            assertEquals(List.of(2000L),sleeps);assertEquals(2,calls.get());
        }finally{server.stop(0);}
    }
    @Test void timeout_retries_same_payload_and_then_succeeds() throws Exception {
        AtomicInteger calls=new AtomicInteger();List<String> payloads=new java.util.concurrent.CopyOnWriteArrayList<>();
        var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setExecutor(executor);
        server.createContext("/",e->{payloads.add(new String(e.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));
            int n=calls.incrementAndGet();if(n==1)try{Thread.sleep(500);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();}
            try{e.sendResponseHeaders(201,-1);}finally{e.close();}});server.start();
        try {
            var sender=new CorreoPorBrevo("http://127.0.0.1:"+server.getAddress().getPort(),"fake-test-key",Duration.ofMillis(200),
                    new ObjectMapper(),millis->{},bound->0,(id,at)->{});
            var m=new SimpleMailMessage();m.setFrom("from@example.com");m.setTo("to@example.com");m.setText("fixture");sender.send(m);
            assertEquals(2,calls.get());assertEquals(payloads.getFirst(),payloads.getLast());
        }finally{server.stop(0);executor.shutdownNow();}
    }

}
