package com.tbridge.ai.service;

import com.sun.net.httpserver.HttpServer;
import com.tbridge.ai.security.*;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.http.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(JwtTestConfig.class)
class IsolationTest {
 static final String A=token("A"), B=token("B");
 static final AtomicInteger debtStatus=new AtomicInteger(200), llmStatus=new AtomicInteger(200);
 static final Queue<String> prompts=new ConcurrentLinkedQueue<>(), forwarded=new ConcurrentLinkedQueue<>();
 static final AtomicReference<String> override=new AtomicReference<>();
 static final HttpServer debt=server(false), llm=server(true);
 @Autowired TestRestTemplate http;
 static String token(String id) { var c=JwtTestTokens.claims(); c.put("rut","RUT-"+id); c.put("id",id); c.put("sub",id); return JwtTestTokens.token(c,JwtTestTokens.PAIR,JwtTestTokens.publicKey(JwtTestTokens.PAIR).getKeyID(),"ES256"); }
 static HttpServer server(boolean provider) {
  try {
   var s=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
   s.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
   s.createContext("/", x -> {
    String body; int status;
    if (provider) {
     String input=new String(x.getRequestBody().readAllBytes(),StandardCharsets.UTF_8); prompts.add(input); status=llmStatus.get();
     String marker=input.contains("Empresa-A")?"Empresa-A":"Empresa-B";
     body="{\"id\":\"chat_1\",\"object\":\"chat.completion\",\"created\":1789923791,\"model\":\"test\",\"choices\":[{\"index\":0,\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\""+marker+"\"}}]}";
    } else {
     String auth=x.getRequestHeaders().getFirst("Authorization"); forwarded.add(auth); status=debtStatus.get();
     String marker=("Bearer "+A).equals(auth)?"Empresa-A":"Empresa-B";
     body=override.get();
     if(body==null) body="{\"_embedded\":{\"debts\":[{\"acreedor\":\""+marker+"\",\"concepto\":\"Servicio\",\"estado\":\"open\",\"moneda\":\"CLP\",\"montoOriginal\":10,\"saldo\":10,\"totalHoy\":10,\"interesMora\":0,\"deudorRut\":\"RUT-MARKER\",\"email\":\"EMAIL-MARKER\"}]}}";
    }
    byte[] bytes=body.getBytes(StandardCharsets.UTF_8); x.getResponseHeaders().set("Content-Type","application/json"); x.sendResponseHeaders(status,bytes.length); x.getResponseBody().write(bytes); x.close();
   }); s.start(); return s;
  } catch(Exception e) { throw new IllegalStateException(e); }
 }
 @DynamicPropertySource static void properties(DynamicPropertyRegistry r) {
  r.add("app.llm.provider",()->"xai");
  r.add("app.debt-url",()->"http://127.0.0.1:"+debt.getAddress().getPort());
  r.add("app.llm.xai.base-url",()->"http://127.0.0.1:"+llm.getAddress().getPort()+"/v1");
  r.add("app.llm.xai.api-key",()->"fake-provider-key"); r.add("app.llm.xai.model",()->"test");
 }
 @BeforeEach void reset() { debtStatus.set(200); llmStatus.set(200); override.set(null); prompts.clear(); forwarded.clear(); }
 ResponseEntity<Map> ask(String token) {
  var h=new HttpHeaders(); h.setBearerAuth(token); h.setContentType(MediaType.APPLICATION_JSON); h.set("X-User-Rut","RUT-B");
  return http.postForEntity("/api/ai/chat?rut=RUT-B",new HttpEntity<>(Map.of("message","cuanto debo", "rut","RUT-B", "messages",List.of()),h),Map.class);
 }
 void check(String token,String own,String other) { var response=ask(token); assertEquals(200,response.getStatusCode().value()); assertTrue(response.getBody().get("reply").toString().contains(own)); assertFalse(response.getBody().get("reply").toString().contains(other)); }
 @Test void sequential_and_provider_projection() {
  check(A,"Empresa-A","Empresa-B"); check(B,"Empresa-B","Empresa-A"); check(A,"Empresa-A","Empresa-B");
  assertEquals(3,prompts.size());
  for(String p:prompts) { assertFalse(p.contains(A)); assertFalse(p.contains(B)); assertFalse(p.contains("RUT-MARKER")); assertFalse(p.contains("EMAIL-MARKER")); assertFalse(p.contains("Empresa-A")&&p.contains("Empresa-B")); }
  assertEquals(List.of("Bearer "+A,"Bearer "+B,"Bearer "+A),new ArrayList<>(forwarded));
 }
 @org.junit.jupiter.params.ParameterizedTest
 @org.junit.jupiter.params.provider.ValueSource(ints={200,400})
 void concurrent_isolation(int providerStatus) throws Exception {
  llmStatus.set(providerStatus);
  try(var pool=Executors.newVirtualThreadPerTaskExecutor()) {
   var barrier=new CyclicBarrier(2);
   var a=pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);check(A,"Empresa-A","Empresa-B");return true;});
   var b=pool.submit(()->{barrier.await(5,TimeUnit.SECONDS);check(B,"Empresa-B","Empresa-A");return true;});
   assertTrue(a.get(20,TimeUnit.SECONDS));assertTrue(b.get(20,TimeUnit.SECONDS));
  }
 }
 @Test void local_isolation_after_provider_failure() { llmStatus.set(400); check(A,"Empresa-A","Empresa-B"); check(B,"Empresa-B","Empresa-A"); }
 @Test void genuine_empty_and_unknown_identity_are_distinct() {
  override.set("{\"_links\":{\"self\":{\"href\":\"/api/debts\"}}}"); assertEquals(200,ask(A).getStatusCode().value());
  prompts.clear(); debtStatus.set(404); override.set("{\"error\":\"No hay deudas a tu nombre\"}");
  var response=ask(A); assertEquals(404,response.getStatusCode().value());assertTrue(response.getBody().containsKey("error"));assertTrue(prompts.isEmpty());
 }
 @Test void dependency_rejections_preserved_before_provider() {
  for(int code:List.of(401,403,500,503)) { debtStatus.set(code);var response=ask(A); assertEquals(code>=500?503:code,response.getStatusCode().value()); assertTrue(response.getBody().containsKey("error")); }
  assertTrue(prompts.isEmpty());
 }
 @AfterAll static void close() {debt.stop(0);llm.stop(0);}
}
