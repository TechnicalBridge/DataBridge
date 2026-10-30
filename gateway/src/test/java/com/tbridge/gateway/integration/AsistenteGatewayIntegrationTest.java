package com.tbridge.gateway.integration;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.web.reactive.server.WebTestClient;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
class AsistenteGatewayIntegrationTest {
 static final AtomicInteger status=new AtomicInteger(401);
 static final AtomicReference<String> header=new AtomicReference<>();
 static final HttpServer upstream=start();
 @Autowired WebTestClient http;
 static HttpServer start() {
  try { var s=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
   s.createContext("/api/ai/chat",x->{header.set(x.getRequestHeaders().getFirst("Authorization"));byte[] b="{\"error\":\"Error seguro\"}".getBytes(StandardCharsets.UTF_8);x.getResponseHeaders().set("Content-Type","application/json");x.sendResponseHeaders(status.get(),b.length);x.getResponseBody().write(b);x.close();});s.start();return s;
  } catch(Exception e){throw new IllegalStateException(e);}
 }
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r){r.add("app.servicios.ai",()->"http://127.0.0.1:"+upstream.getAddress().getPort());}
 @Test void forwards_authorization_and_preserves_errors() {
  for(int code:new int[]{401,403,404,502,503}) {
   status.set(code);http.post().uri("/api/ai/chat").header("Authorization","Bearer ficticio").bodyValue("{}").exchange().expectStatus().isEqualTo(code).expectBody().jsonPath("$.error").isEqualTo("Error seguro");assertEquals("Bearer ficticio",header.get());
  }
 }
 @AfterAll static void close(){upstream.stop(0);}
}
