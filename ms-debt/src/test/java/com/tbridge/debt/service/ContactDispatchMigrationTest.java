package com.tbridge.debt.service;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import static org.junit.jupiter.api.Assertions.*;

class ContactDispatchMigrationTest {
    @Test void retry_migration_preserves_pending_jobs_and_supports_old_insert_shape() {
        var source=new JdbcDataSource();
        source.setURL("jdbc:h2:mem:"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        var db=new JdbcTemplate(source);
        db.execute("create table contact_jobs(id bigint primary key,state varchar(12),created_at datetime(6),updated_at datetime(6))");
        db.execute("create index ix_job_state on contact_jobs(state,id)");
        db.update("insert into contact_jobs values(1,'QUEUED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        db.update("insert into contact_jobs values(2,'UNCERTAIN',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V15__contact_dispatch_retry.sql")).execute(source);
        assertEquals("QUEUED",db.queryForObject("select state from contact_jobs where id=1",String.class));
        assertEquals("UNCERTAIN",db.queryForObject("select state from contact_jobs where id=2",String.class));
        assertEquals(2,db.queryForObject("select count(*) from contact_jobs where dispatch_attempts=0 and next_attempt_at is not null",Integer.class));
        db.update("insert into contact_jobs(id,state,created_at,updated_at) values(3,'QUEUED',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP)");
        assertEquals(3,db.queryForObject("select count(*) from contact_jobs where dispatch_attempts=0 and next_attempt_at is not null",Integer.class));
    }
}
