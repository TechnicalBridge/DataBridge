package com.tbridge.auth.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.auth.client.DeudasClient;
import com.tbridge.auth.model.StaffUser;
import com.tbridge.auth.repository.StaffUserRepository;
import com.tbridge.auth.security.JwtService;
import com.tbridge.auth.security.JwtTestTokens;
import com.tbridge.auth.service.MailService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.containers.MySQLContainer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Base de las pruebas de integracion y seguridad de ms-auth.
 *
 * <p>Levanta el servicio completo contra un MySQL real: en CI con Testcontainers
 * ({@code mysql:8.4}) y en local con {@code DB_TEST_URL}, {@code DB_TEST_USER} y
 * {@code DB_TEST_PASSWORD}. Sin base y fuera de CI las clases se omiten, para
 * que {@code mvn test} siga corriendo rapido en cualquier equipo.
 *
 * <p>Las dos dependencias externas se reemplazan por mocks: ms-debt (correo del
 * deudor y empresa) y el correo. El mock de {@link MailService} es deliberado:
 * ninguna prueba abre una conexion SMTP ni manda un correo real.
 */
@SpringBootTest
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
public abstract class BaseIntegracionMySql {

    protected static final String RUT_DEUDOR = "18905214-6";
    protected static final String RUT_OTRO_DEUDOR = "16482337-7";
    protected static final String CORREO_DEUDOR = "valentina.soto@example.com";
    protected static final String CORREO_OTRO_DEUDOR = "otro.deudor@example.com";
    protected static final String RUT_EMPRESA = "76418902-7";
    protected static final String ADMIN_EMAIL = "admin@example.com";
    protected static final String EMPRESA_EMAIL = "empresa@example.com";
    protected static final String CLAVE_INTERNA = "clave-interna-de-prueba";

    private static final AtomicInteger ORIGEN = new AtomicInteger(1);

    /** Claves EC efimeras, escritas como PEM para que las cargue el JwtKeyConfig real. */
    private static final Path DIR_CLAVES;

    static {
        try {
            DIR_CLAVES = Files.createTempDirectory("auth-integracion-claves");
            var par = JwtTestTokens.PAIR;
            Files.writeString(DIR_CLAVES.resolve("private.pem"), pem("PRIVATE KEY", par.getPrivate().getEncoded()));
            Files.writeString(DIR_CLAVES.resolve("public.pem"), pem("PUBLIC KEY", par.getPublic().getEncoded()));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static String pem(String etiqueta, byte[] der) {
        String base64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der);
        return "-----BEGIN " + etiqueta + "-----\n" + base64 + "\n-----END " + etiqueta + "-----";
    }

    /** Corre con la base externa de {@code DB_TEST_*} o en CI, donde si hay Docker. */
    public static boolean hayBaseDeDatos() {
        return System.getenv("DB_TEST_URL") != null || "true".equalsIgnoreCase(System.getenv("CI"));
    }

    /**
     * El nombre de la base en una URL JDBC, sin parametros ni credenciales.
     *
     * <p>Se usa solo para decidir si la base externa es desechable. Si la URL
     * no trae un nombre reconocible devuelve una cadena vacia.
     */
    static String nombreDeBase(String url) {
        if (url == null) {
            return "";
        }
        int esquema = url.indexOf("://");
        if (esquema < 0) {
            return "";
        }
        String resto = url.substring(esquema + 3);
        int barra = resto.indexOf('/');
        if (barra < 0) {
            return "";
        }
        String nombre = resto.substring(barra + 1);
        int fin = nombre.length();
        int parametros = nombre.indexOf('?');
        if (parametros >= 0 && parametros < fin) {
            fin = parametros;
        }
        int opciones = nombre.indexOf(';');
        if (opciones >= 0 && opciones < fin) {
            fin = opciones;
        }
        return nombre.substring(0, fin).strip();
    }

    /**
     * Falla si {@code DB_TEST_URL} apunta a una base que no es de pruebas.
     *
     * <p>{@code limpiarYPreparar} borra personas, sesiones, codigos, enlaces y
     * bitacoras antes de cada caso. Para no tocar una base real, el nombre debe
     * terminar en {@code _pruebas} o {@code _test}. La base de Testcontainers
     * queda fuera de esta validacion porque es nueva y desechable.
     */
    static void validarBaseDePruebas(String url) {
        if (url == null || url.isBlank()) {
            return;
        }
        String nombre = nombreDeBase(url);
        String enMinusculas = nombre.toLowerCase(Locale.ROOT);
        if (enMinusculas.endsWith("_pruebas") || enMinusculas.endsWith("_test")) {
            return;
        }
        String cual = nombre.isEmpty() ? "sin nombre" : "'" + nombre + "'";
        throw new IllegalStateException("DB_TEST_URL apunta a la base " + cual
                + ", pero las pruebas borran sus tablas antes de cada caso. Usa una base cuyo nombre "
                + "termine en _pruebas o _test (por ejemplo, tb_auth_pruebas), o deja DB_TEST_URL "
                + "sin definir para usar Testcontainers.");
    }

    /** MySQL efimero; {@code null} cuando se usa un MySQL externo por variables de entorno. */
    private static MySQLContainer<?> MYSQL;

    protected static synchronized MySQLContainer<?> contenedor() {
        if (MYSQL == null) {
            MYSQL = new MySQLContainer<>("mysql:8.4")
                    .withDatabaseName("tb_auth")
                    .withUsername("tbridge")
                    .withPassword("tbridge-test")
                    //  En Windows el redo log por omision tarda varios minutos;
                    //  con 256 MB el contenedor arranca en ~90 s.
                    .withCommand("--innodb-redo-log-capacity=268435456")
                    .withStartupTimeout(Duration.ofMinutes(5));
            MYSQL.start();
        }
        return MYSQL;
    }

    @DynamicPropertySource
    static void propiedades(DynamicPropertyRegistry propiedades) {
        String urlExterna = System.getenv("DB_TEST_URL");
        if (urlExterna == null) {
            MySQLContainer<?> mysql = contenedor();
            propiedades.add("spring.datasource.url", mysql::getJdbcUrl);
            propiedades.add("spring.datasource.username", mysql::getUsername);
            propiedades.add("spring.datasource.password", mysql::getPassword);
        } else {
            validarBaseDePruebas(urlExterna);
            propiedades.add("spring.datasource.url", () -> urlExterna);
            propiedades.add("spring.datasource.username",
                    () -> System.getenv().getOrDefault("DB_TEST_USER", "tbridge"));
            propiedades.add("spring.datasource.password",
                    () -> System.getenv().getOrDefault("DB_TEST_PASSWORD", ""));
        }
        propiedades.add("spring.flyway.enabled", () -> "true");
        propiedades.add("app.demo.datos", () -> "false");
        propiedades.add("app.admin-email", () -> "");
        propiedades.add("app.internal-key", () -> CLAVE_INTERNA);
        propiedades.add("app.public-url", () -> "http://localhost:8080");
        propiedades.add("app.cookie-secure", () -> "false");
        propiedades.add("spring.mail.host", () -> "");
        propiedades.add("jwt.key-directory", () -> DIR_CLAVES.toString());
        propiedades.add("jwt.ttl-minutes", () -> "15");
        propiedades.add("app.identifier-limit.capacity", () -> "1000");
        propiedades.add("app.identifier-limit.window-seconds", () -> "60");
        propiedades.add("app.identifier-limit.max-block-seconds", () -> "900");
        propiedades.add("app.debt-url", () -> "http://127.0.0.1:1");
    }

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected JwtService jwtService;
    @Autowired protected StaffUserRepository personal;

    @MockitoBean protected DeudasClient deudas;
    @MockitoBean protected MailService correo;

    protected StaffUser admin;
    protected StaffUser usuario;

    @BeforeEach
    void limpiarYPreparar() {
        validarBaseDePruebas(System.getenv("DB_TEST_URL"));

        jdbc.execute("DELETE FROM sessions");
        jdbc.execute("DELETE FROM magic_links");
        jdbc.execute("DELETE FROM access_codes");
        jdbc.execute("DELETE FROM access_log");
        jdbc.execute("DELETE FROM admin_log");
        jdbc.execute("DELETE FROM staff_users");

        admin = sembrarAdmin();
        usuario = sembrarUsuario();

        when(deudas.correoDelDeudor(RUT_DEUDOR)).thenReturn(Optional.of(CORREO_DEUDOR));
        when(deudas.correoDelDeudor(RUT_OTRO_DEUDOR)).thenReturn(Optional.of(CORREO_OTRO_DEUDOR));
        when(deudas.empresa(RUT_EMPRESA))
                .thenReturn(Optional.of(new DeudasClient.Empresa(RUT_EMPRESA, "Patrimonio Inmuebles", true)));
    }

    private StaffUser sembrarAdmin() {
        StaffUser persona = new StaffUser();
        persona.setEmail(ADMIN_EMAIL);
        persona.setFullName("Admin DataBridge");
        persona.setRole(StaffUser.Role.admin);
        return personal.save(persona);
    }

    private StaffUser sembrarUsuario() {
        StaffUser persona = new StaffUser();
        persona.setEmail(EMPRESA_EMAIL);
        persona.setFullName("Empresa DataBridge");
        persona.setRole(StaffUser.Role.usuario);
        persona.setOrgRut(RUT_EMPRESA);
        return personal.save(persona);
    }

    protected String tokenAdmin() {
        return jwtService.issue(String.valueOf(admin.getId()), admin.getEmail(), "ADMIN", admin.getFullName(), null);
    }

    protected String tokenEmpresa() {
        return jwtService.issue(String.valueOf(usuario.getId()), usuario.getEmail(), "CREDITOR",
                usuario.getFullName(), RUT_EMPRESA);
    }

    protected String tokenDeudor() {
        return tokenDeudor(RUT_DEUDOR, CORREO_DEUDOR);
    }

    protected String tokenDeudor(String rut, String correoDeudor) {
        return jwtService.issue(rut, correoDeudor, "DEBTOR", "Deudor de prueba", rut);
    }

    /** Un origen distinto por prueba: el freno de fuerza bruta no mezcla intentos. */
    protected RequestPostProcessor origen() {
        int n = ORIGEN.getAndIncrement();
        String ip = "10." + (n / 65536 % 256) + "." + (n / 256 % 256) + "." + (n % 256);
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    protected String cuerpoJson(Object valor) {
        try {
            return json.writeValueAsString(valor);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Emite un codigo por la puerta interna, sin correo: el propio endpoint lo devuelve. */
    protected String codigoInterno(String rut) throws Exception {
        String cuerpo = cuerpoJson(Map.of(
                "rut", rut,
                "canales", List.of("whatsapp"),
                "paraQue", "prueba de integracion"));
        String respuesta = mvc.perform(post("/internal/codigos")
                        .header("X-Internal-Key", CLAVE_INTERNA)
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(cuerpo))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return json.readTree(respuesta).get("codigo").asText();
    }

    /**
     * Pide el respaldo de un deudor y devuelve el token del enlace. El correo
     * esta mockeado: se captura lo que se habria mandado, sin abrir SMTP.
     */
    protected String pedirEnlaceDeDeudor(String rut) throws Exception {
        clearInvocations(correo);
        String cuerpo = cuerpoJson(Map.of("rut", rut));
        mvc.perform(post("/api/auth/enlace")
                        .with(origen())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(cuerpo))
                .andExpect(status().isAccepted());
        org.mockito.ArgumentCaptor<String> url = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(correo).enviarAcceso(eq(CORREO_DEUDOR), org.mockito.ArgumentMatchers.anyString(),
                url.capture(), eq(15L));
        String enlace = url.getValue();
        return enlace.substring(enlace.indexOf("token=") + "token=".length());
    }

    /**
     * Pide un codigo al correo de una persona del personal y lo devuelve. El
     * correo esta mockeado: ninguna prueba manda un mensaje real.
     */
    protected String pedirCodigoDePersonal(String email) throws Exception {
        clearInvocations(correo);
        String cuerpo = cuerpoJson(Map.of("correo", email));
        mvc.perform(post("/api/auth/codigo")
                        .with(origen())
                        .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .content(cuerpo))
                .andExpect(status().isAccepted());
        org.mockito.ArgumentCaptor<String> codigo = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(correo).enviarAcceso(eq(email), codigo.capture(), isNull(), eq(15L));
        return codigo.getValue();
    }
}
