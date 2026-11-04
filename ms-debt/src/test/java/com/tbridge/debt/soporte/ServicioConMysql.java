package com.tbridge.debt.soporte;

import com.tbridge.debt.security.JwtTestConfig;
import com.tbridge.debt.security.JwtTestIssuer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.NestedTestConfiguration;
import org.testcontainers.containers.MySQLContainer;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/**
 * ms-debt levantado de verdad, con un MySQL de verdad en un contenedor. De aqui
 * parten las clases @Nested de integracion, seguridad y rendimiento de cada
 * controlador.
 *
 * <p>El contenedor se levanta una sola vez, la primera vez que se necesita, y lo
 * comparten todas las pruebas de esa corrida. Las pruebas de siempre no llegan a
 * cargar esta clase, asi que no necesitan Docker.
 *
 * <p>OVERRIDE: una clase @Nested heredaria el @WebMvcTest de la clase que la
 * contiene. Con esto, usa solo lo que dice aqui.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("integracion")
@Import(JwtTestConfig.class)
@NestedTestConfiguration(NestedTestConfiguration.EnclosingConfiguration.OVERRIDE)
public abstract class ServicioConMysql {

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4").withDatabaseName("tb_debt");

    static {
        MYSQL.start();
    }

    @DynamicPropertySource
    static void laBase(DynamicPropertyRegistry propiedades) {
        propiedades.add("spring.datasource.url", MYSQL::getJdbcUrl);
        propiedades.add("spring.datasource.username", MYSQL::getUsername);
        propiedades.add("spring.datasource.password", MYSQL::getPassword);
    }

    @LocalServerPort protected int puerto;
    @Autowired protected JwtTestIssuer jwt;
    @Autowired protected JdbcTemplate base;

    private final HttpClient http = HttpClient.newHttpClient();

    protected String url(String ruta) {
        return "http://localhost:" + puerto + ruta;
    }

    /** Un GET de verdad, por HTTP. Sin token, va sin sesion. */
    protected HttpResponse<String> pedir(String ruta, String token) throws IOException, InterruptedException {
        HttpRequest.Builder peticion = HttpRequest.newBuilder(URI.create(url(ruta)));
        if (token != null) {
            peticion.header("Authorization", "Bearer " + token);
        }
        return http.send(peticion.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    protected String sesionDeAdmin() {
        return jwt.issue("9", "admin@example.com", "ADMIN", "Administrador de DataBridge", null);
    }

    protected String sesionDeDeudor() {
        return jwt.issue("16482337-7", null, "DEBTOR", null, "16482337-7");
    }
}
