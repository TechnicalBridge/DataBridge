package com.tbridge.debt.service;

import com.tbridge.debt.exception.CarteraInvalida;
import com.tbridge.debt.model.ApiKey;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.ApiKeyRepository;
import com.tbridge.debt.repository.OrganizationRepository;
import com.tbridge.debt.util.Hash;
import jakarta.persistence.EntityManager;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real MySQL only; transactions meet immediately before issuing the conditional UPDATE. */
@DataJpaTest(properties = {"spring.flyway.enabled=true", "spring.jpa.hibernate.ddl-auto=validate", "app.cifrado.llave=qa-mysql-only",
        "spring.jpa.show-sql=false", "spring.datasource.hikari.maximum-pool-size=10",
        "spring.jpa.properties.hibernate.session_factory.statement_inspector=com.tbridge.debt.service.ApiKeyMySqlConcurrencyTest$Updates"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({ApiKeyService.class, com.tbridge.debt.config.Cifrado.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnabledIfEnvironmentVariable(named="DB_API_TEST_URL", matches=".+")
class ApiKeyMySqlConcurrencyTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry r) {
        String url = System.getenv("DB_API_TEST_URL");
        if (!url.matches("jdbc:mysql://[^/]+/tb_debt_api_test(?:\\?.*)?"))
            throw new IllegalStateException("DB_API_TEST_URL must name tb_debt_api_test");
        r.add("spring.datasource.url", () -> url);
        r.add("spring.datasource.username", () -> System.getenv("DB_TEST_USER"));
        r.add("spring.datasource.password", () -> System.getenv("DB_TEST_PASSWORD"));
    }
    public static class Updates implements StatementInspector {
        static final AtomicInteger emitted = new AtomicInteger();
        static volatile CyclicBarrier barrier;
        public String inspect(String sql) {
            if (sql.toLowerCase().startsWith("update api_keys") && sql.contains("last_used_at")) {
                emitted.incrementAndGet();
                var b = barrier;
                if (b != null) try { b.await(30, TimeUnit.SECONDS); }
                catch (Exception e) { throw new IllegalStateException(e); }
            }
            return sql;
        }
    }
    @Autowired ApiKeyService service;
    @Autowired ApiKeyRepository keys;
    @Autowired OrganizationRepository organizations;
    @Autowired JdbcTemplate jdbc;
    @Autowired EntityManager em;
    @Autowired PlatformTransactionManager manager;
    long id;
    final String raw = "tbk_synthetic_mysql_only";
    TransactionTemplate tx() { return new TransactionTemplate(manager); }
    @BeforeEach void fixture() {
        Updates.barrier=null;
        jdbc.execute("CREATE TABLE IF NOT EXISTS qa_api_writes (id BIGINT AUTO_INCREMENT PRIMARY KEY)");
        jdbc.execute("DROP TRIGGER IF EXISTS qa_api_audit");
        jdbc.execute("CREATE TRIGGER qa_api_audit AFTER UPDATE ON api_keys FOR EACH ROW BEGIN IF NOT (OLD.last_used_at <=> NEW.last_used_at) THEN INSERT INTO qa_api_writes VALUES (NULL); END IF; END");
        tx().executeWithoutResult(s -> {
            keys.deleteAllInBatch(); organizations.deleteAllInBatch();
            var org=new Organization(); org.setRut("76123456-0"); org.setLegalName("QA"); org.setTradeName("QA");
            organizations.saveAndFlush(org);
            var key=new ApiKey(); key.setOrganization(org); key.setName("QA"); key.setPrefix("tbk_syntheti"); key.setKeyHash(Hash.sha256(raw));
            id=keys.saveAndFlush(key).getId();
        });
        jdbc.update("DELETE FROM qa_api_writes"); Updates.emitted.set(0);
    }
    @AfterEach void cleanup() { Updates.barrier=null; jdbc.execute("DROP TRIGGER IF EXISTS qa_api_audit"); }
    Instant used() { return jdbc.queryForObject("SELECT last_used_at FROM api_keys WHERE id=?", (r,n)->r.getTimestamp(1)==null?null:r.getTimestamp(1).toInstant(), id); }
    int writes() { return jdbc.queryForObject("SELECT COUNT(*) FROM qa_api_writes", Integer.class); }
    void authenticate() { service.autenticar("Bearer "+raw); }
    @Test void first_recent_expired_and_managed_flush() {
        assertNull(used());
        tx().executeWithoutResult(s -> { var managed=keys.findById(id).orElseThrow(); authenticate(); assertNull(managed.getLastUsedAt()); em.flush(); });
        Instant first=used(); assertNotNull(first); assertEquals(1,writes()); assertEquals(1,Updates.emitted.get());
        authenticate(); assertEquals(first,used()); assertEquals(1,writes()); assertEquals(1,Updates.emitted.get());
        jdbc.update("UPDATE api_keys SET last_used_at=DATE_SUB(UTC_TIMESTAMP(), INTERVAL 301 SECOND) WHERE id=?",id);
        jdbc.update("DELETE FROM qa_api_writes"); authenticate(); assertTrue(used().isAfter(first.minusSeconds(1))); assertEquals(1,writes());
    }
    @Test void eight_real_transactions_write_once() throws Exception {
        Updates.barrier=new CyclicBarrier(8);
        try (var pool=Executors.newFixedThreadPool(8)) {
            var results=new ArrayList<Future<?>>();
            for(int n=0;n<8;n++) results.add(pool.submit(this::authenticate));
            for(var result:results) result.get(60,TimeUnit.SECONDS);
        } finally { Updates.barrier=null; }
        assertEquals(8,Updates.emitted.get()); assertEquals(1,writes()); assertNotNull(used());
        System.out.println("QA343 concurrent transactions=8 UPDATE statements=8 effective timestamp changes=1");
    }
    @Test void exact_repository_boundary() {
        Instant now=Instant.parse("2026-11-22T12:00:00Z");
        tx().executeWithoutResult(s -> assertEquals(1,keys.registrarUsoSiVencido(id,now,now.minusSeconds(300))));
        tx().executeWithoutResult(s -> assertEquals(0,keys.registrarUsoSiVencido(id,now.plusSeconds(299),now.minusSeconds(1))));
        tx().executeWithoutResult(s -> assertEquals(1,keys.registrarUsoSiVencido(id,now.plusSeconds(300),now)));
        assertEquals(2,writes());
    }
    @Test void invalid_revoked_and_suspended_have_no_writes_or_authorization_cache() {
        assertEquals(401,assertThrows(CarteraInvalida.class,()->service.autenticar("Bearer unknown")).getStatus());
        authenticate();
        jdbc.update("UPDATE api_keys SET revoked_at=UTC_TIMESTAMP() WHERE id=?",id);
        assertEquals(401,assertThrows(CarteraInvalida.class,this::authenticate).getStatus());
        jdbc.update("UPDATE api_keys SET revoked_at=NULL,last_used_at=NULL WHERE id=?",id);
        jdbc.update("UPDATE organizations SET status='suspended'");
        int before=writes();
        assertEquals(403,assertThrows(CarteraInvalida.class,this::authenticate).getStatus());
        assertEquals(before,writes()); assertNull(used());
    }
}
