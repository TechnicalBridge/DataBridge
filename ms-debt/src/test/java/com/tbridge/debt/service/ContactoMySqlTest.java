package com.tbridge.debt.service;
import com.tbridge.debt.client.AuthClient;
import com.tbridge.debt.config.Cifrado;
import com.tbridge.debt.model.Debtor;
import com.tbridge.debt.repository.*;
import com.tbridge.debt.security.JwtPrincipal;
import com.tbridge.debt.exception.ApiException;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@DataJpaTest(properties={"spring.flyway.enabled=true","spring.jpa.hibernate.ddl-auto=validate",
        "app.contact-verification-key=test-key-generated-for-471-verification-only", "logging.level.org.hibernate.SQL=OFF"})
@AutoConfigureTestDatabase(replace=AutoConfigureTestDatabase.Replace.NONE)
@Import({CorreoPersonalService.class,ContactoPersonalService.class,ContactoRateLimit.class,ContactoHuella.class,Cifrado.class,CarteraIntakeService.class,com.fasterxml.jackson.databind.ObjectMapper.class})
@ActiveProfiles("test")
@EnabledIf("hayDocker")
@Transactional(propagation=Propagation.NOT_SUPPORTED)
class ContactoMySqlTest {
    static MySQLContainer<?> db;
    static boolean hayDocker() {
        return "true".equalsIgnoreCase(System.getenv("CI")) || org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
    }
    @DynamicPropertySource static void props(DynamicPropertyRegistry r) {
        db=new MySQLContainer<>("mysql:8.4");db.start();
        r.add("spring.datasource.url",db::getJdbcUrl);r.add("spring.datasource.username",db::getUsername);
        r.add("spring.datasource.password",db::getPassword);
    }
    @Autowired CorreoPersonalService service;
    @Autowired DebtorRepository debtors;
    @Autowired EmailChangeRepository changes;
    @Autowired PlatformTransactionManager manager;
    @Autowired JdbcTemplate jdbc;
    @Autowired CarteraIntakeService intake;
    @Autowired OrganizationRepository organizations;
    @Autowired ContactoPersonalService phone;
    @MockitoBean EventosService events;
    @MockitoBean AuthClient auth;
    @MockitoBean MisDatosService datos;
    JwtPrincipal user;
    String code;
    String request;
    @BeforeEach void setup() {
        jdbc.update("DELETE FROM contact_rate_limits");
        new TransactionTemplate(manager).executeWithoutResult(tx -> {
            Debtor d=new Debtor();d.setRut(rutDePrueba());d.setFullName("Prueba contacto");d.setEmail("antes@correo.cl");
            debtors.saveAndFlush(d);
            user=new JwtPrincipal(d.getRut(),null,"DEBTOR",null,d.getRut());
        });
        doAnswer(i -> {code=i.getArgument(1);return null;}).when(auth).verificarCorreo(anyString(),anyString());
        request=service.solicitar(user,"nuevo@correo.cl",UUID.randomUUID().toString()).solicitudId();
    }
    @Test void persistsEncryptedChallengeAndFailedAttemptsAcrossTransactions() {
        String encrypted=jdbc.queryForObject("select new_email from email_changes where id=?",String.class,request);
        assertTrue(encrypted.startsWith("enc:v1:")); assertFalse(encrypted.contains("nuevo@correo.cl"));
        for(int i=0;i<5;i++) assertThrows(ApiException.class,()->service.confirmar(user,request,"AAAAAA"));
        assertEquals(5,jdbc.queryForObject("select attempts from email_changes where id=?",Integer.class,request));
        assertThrows(ApiException.class,()->service.confirmar(user,request,code));
        assertEquals("antes@correo.cl",debtors.findByRut(user.rut()).orElseThrow().getEmail());
    }
    @Test void concurrentConfirmationIsSingleUseAndAtomicWithAuditAndOutbox() throws Exception {
        ExecutorService pool=Executors.newFixedThreadPool(2);
        CountDownLatch start=new CountDownLatch(1);
        Callable<Boolean> confirm=()->{start.await();try {service.confirmar(user,request,code);return true;}catch(ApiException e){return false;}};
        try {
            Future<Boolean> a=pool.submit(confirm), b=pool.submit(confirm);start.countDown();
            assertNotEquals(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
        } finally {pool.shutdownNow();}
        Debtor changed=debtors.findByRut(user.rut()).orElseThrow();
        assertEquals("nuevo@correo.cl",changed.getEmail());assertNotNull(changed.getEmailUpdatedAt());
        assertEquals(1,jdbc.queryForObject("select count(*) from contact_updates where debtor_id=?",Integer.class,changed.getId()));
        assertEquals(1,jdbc.queryForObject("select count(*) from contact_notices where id=?",Integer.class,request));
    }
    @Test void replacedRequestCannotConfirmAcrossTransactions() {
        String old=request, oldCode=code;
        String next=service.solicitar(user,"otro@correo.cl",UUID.randomUUID().toString()).solicitudId();
        assertThrows(ApiException.class,()->service.confirmar(user,old,oldCode));
        service.confirmar(user,next,code);
        assertEquals("otro@correo.cl",debtors.findByRut(user.rut()).orElseThrow().getEmail());
    }
    @Test void mysqlRateLimitIsSharedAcrossServiceCalls() {
        service.solicitar(user,"dos@correo.cl",UUID.randomUUID().toString());
        service.solicitar(user,"tres@correo.cl",UUID.randomUUID().toString());
        assertEquals(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                assertThrows(ApiException.class,()->service.solicitar(user,"cuatro@correo.cl",UUID.randomUUID().toString())).getStatus());
    }

    static final java.util.concurrent.atomic.AtomicInteger number=new java.util.concurrent.atomic.AtomicInteger(30000000);
    static String rutDePrueba() {
        String base=String.valueOf(number.incrementAndGet());int sum=0,multiplier=2;
        for(int i=base.length()-1;i>=0;i--){sum+=(base.charAt(i)-'0')*multiplier;multiplier=multiplier==7?2:multiplier+1;}
        int digit=11-sum%11;return base+"-"+(digit==11?"0":digit==10?"K":digit);
    }
    @Test void twoCreditorsCannotOverwriteVerifiedContactAndBatchStaysIdempotent() throws Exception {
        service.confirmar(user,request,code);
        phone.telefono(user,"+56922222222");
        for(int i=0;i<2;i++) {
            var org=new com.tbridge.debt.model.Organization();org.setRut(rutDePrueba());org.setLegalName("Acreedor prueba");org.setTradeName("Acreedor prueba");
            organizations.saveAndFlush(org);
            var payload=new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                    {"version":"1.0","lote":{"id_externo":"LOTE-%s","fecha_corte":"2026-11-20","acreedor":{"rut":"%s"}},
                     "deudas":[{"id_externo":"DEUDA-%s","moneda":"CLP","concepto":"Prueba cartera",
                       "deudor":{"rut":"%s","tipo":"persona","nombre":"Nombre actualizado","correo":"importado@correo.cl","telefono":"+56911111111"},
                       "cargos":[{"concepto":"Cuota","monto":10000,"fecha_vencimiento":"2026-08-01"}]}]}
                    """.formatted(org.getId(),org.getRut(),org.getId(),user.rut()));
            assertEquals(1,intake.recibir(org,payload,com.tbridge.debt.model.Batch.Source.api).aceptadas());
            assertTrue(intake.recibir(org,payload,com.tbridge.debt.model.Batch.Source.api).repetido());
        }
        Debtor d=debtors.findByRut(user.rut()).orElseThrow();
        assertEquals("nuevo@correo.cl",d.getEmail());assertEquals("+56922222222",d.getPhone());
        assertEquals("Nombre actualizado",d.getFullName());
        assertEquals(1,jdbc.queryForObject("select count(*) from debtors where rut=?",Integer.class,user.rut()));
        assertEquals(2,jdbc.queryForObject("select count(*) from debts where debtor_id=?",Integer.class,d.getId()));
    }
}
