package com.tbridge.ai.client;
import com.tbridge.ai.exception.DeudasException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.*;
import org.springframework.test.web.client.*;
import org.springframework.web.client.RestClient;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static com.tbridge.ai.exception.DeudasException.Categoria.*;
class DeudasClientTest {
 MockRestServiceServer server; DeudasClient client;
 static final String DEBT = "{\"acreedor\":\"Empresa ficticia\",\"concepto\":\"Servicio\",\"estado\":\"open\",\"moneda\":\"CLP\",\"montoOriginal\":10,\"saldo\":10,\"totalHoy\":10,\"interesMora\":0}";
 @BeforeEach void setup() { var b=RestClient.builder(); server=MockRestServiceServer.bindTo(b).build(); client=new DeudasClient(b,"http://debt/"); }
 void reply(int status,String body) { server.expect(requestTo("http://debt/api/debts")).andExpect(header("Authorization","Bearer ficticio")).andRespond(withStatus(HttpStatusCode.valueOf(status)).body(body).contentType(MediaType.APPLICATION_JSON)); }
 void fails(DeudasException.Categoria category) { assertEquals(category,assertThrows(DeudasException.class,()->client.deudasDelDeudor("Bearer ficticio")).categoria()); server.verify(); }
 @Test void forwards_and_projects_only_context() { reply(200,"{\"_embedded\":{\"debts\":["+DEBT+"]}}"); assertEquals(1,client.deudasDelDeudor("Bearer ficticio").size()); server.verify(); }
 @Test void empty_hal() { reply(200,"{\"_links\":{\"self\":{\"href\":\"/api/debts\"}}}"); assertEquals(List.of(),client.deudasDelDeudor("Bearer ficticio")); }
 @Test void explicit_empty() { reply(200,"{\"_embedded\":{\"debts\":[]}}"); assertEquals(List.of(),client.deudasDelDeudor("Bearer ficticio")); }
 @Test void unauthenticated() { reply(401,"TOKEN-MARKER-SECRET"); fails(NO_AUTENTICADO); }
 @Test void forbidden() { reply(403,"RUT-MARKER"); fails(NO_AUTORIZADO); }
 @Test void missing_debtor() { reply(404,"{\"error\":\"No hay deudas a tu nombre\"}"); fails(DEUDOR_NO_REGISTRADO); }
 @ParameterizedTest @ValueSource(strings={"", "<html/>", "{}", "{\"error\":\"No existe esa ruta\"}", "{\"error\":\"No hay deudas a tu nombre extra\"}"})
 void unknown_404(String body) { reply(404,body); fails(PROTOCOLO); }
 @ParameterizedTest @ValueSource(ints={500,503}) void unavailable(int status) { reply(status,"PROMPT-MARKER"); fails(NO_DISPONIBLE); }
 @ParameterizedTest @ValueSource(ints={204,302,400,429}) void wrong_status(int status) { reply(status,""); fails(PROTOCOLO); }
 @ParameterizedTest @ValueSource(strings={"", "null", "{", "{}", "{\"_embedded\":0}", "{\"_embedded\":{\"debts\":{}}}", "{\"_embedded\":{\"debts\":[0]}}", "{\"_embedded\":{\"debts\":[{}]}}"})
 void invalid_protocol(String body) { reply(200,body); fails(PROTOCOLO); }
 @Test void invalid_consumed_field() { reply(200,"{\"_embedded\":{\"debts\":["+DEBT.replace("\"saldo\":10","\"saldo\":\"bad\"")+"]}}"); fails(PROTOCOLO); }
 @Test void connection_or_timeout() { server.expect(requestTo("http://debt/api/debts")).andRespond(withException(new java.net.SocketTimeoutException("TOKEN-MARKER-SECRET"))); fails(NO_DISPONIBLE); }
 @Test void no_credentials_no_request() { assertThrows(DeudasException.class,()->client.deudasDelDeudor(null)); server.verify(); }
}
