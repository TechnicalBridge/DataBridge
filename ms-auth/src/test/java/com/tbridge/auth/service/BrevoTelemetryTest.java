package com.tbridge.auth.service;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.auth.controller.BrevoWebhookController;
import java.time.Instant;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class BrevoTelemetryTest {
    private JdbcTemplate db; private BrevoTelemetry telemetry; private MockMvc mvc;
    private static final String SECRET="fixture-webhook-secret-32-characters-only";
    @BeforeEach void setup(){
        var source=new JdbcDataSource();source.setURL("jdbc:h2:mem:"+java.util.UUID.randomUUID()+";MODE=MySQL;DB_CLOSE_DELAY=-1");
        new ResourceDatabasePopulator(new ClassPathResource("db/migration/V10__mail_delivery_events.sql")).execute(source);
        db=new JdbcTemplate(source);telemetry=new BrevoTelemetry(db);
        mvc=MockMvcBuilders.standaloneSetup(new BrevoWebhookController(telemetry,SECRET)).build();
    }
    @Test void rejects_missing_invalid_and_disabled_secret() throws Exception {
        mvc.perform(post("/api/webhooks/brevo").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/webhooks/brevo").header("X-Brevo-Secret","invalid").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        var disabled=MockMvcBuilders.standaloneSetup(new BrevoWebhookController(telemetry,"")).build();
        disabled.perform(post("/api/webhooks/brevo").header("X-Brevo-Secret",SECRET).contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        assertEquals(0,db.queryForObject("select count(*) from mail_delivery_events",Integer.class));
    }
    @Test void events_are_durable_idempotent_and_correlated_without_payload() throws Exception {
        Instant at=Instant.now().minusSeconds(10).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        telemetry.accepted("<opaque-message>",at.minusSeconds(15));
        for(String event:java.util.List.of("delivered","deferred","soft_bounce","hard_bounce","blocked","error")) {
            String body=new ObjectMapper().writeValueAsString(java.util.Map.of("message-id","opaque-message","event",event,
                    "ts_event",at.getEpochSecond(),"email","discard@example.com","subject","discard"));
            for(int n=0;n<2;n++) mvc.perform(post("/api/webhooks/brevo").header("X-Brevo-Secret",SECRET)
                    .contentType("application/json").content(body)).andExpect(status().isNoContent());
        }
        assertEquals(6,db.queryForObject("select count(*) from mail_delivery_events",Integer.class));
        assertEquals(6,db.queryForObject("select count(*) from mail_delivery_events e join mail_acceptances a "
                +"on e.message_id=a.message_id where e.event_at>a.accepted_at",Integer.class));
    }
    @Test void invalid_event_and_missing_timestamp_rejected() throws Exception {
        mvc.perform(post("/api/webhooks/brevo").header("X-Brevo-Secret",SECRET).contentType("application/json")
                .content("{\"event\":\"delivered\"}")).andExpect(status().isBadRequest());
    }
    @Test void out_of_range_timestamp_is_a_client_error() throws Exception {
        mvc.perform(post("/api/webhooks/brevo").header("X-Brevo-Secret",SECRET).contentType("application/json")
                .content("{\"message-id\":\"fixture\",\"event\":\"delivered\",\"ts_event\":9223372036854775807}"))
                .andExpect(status().isBadRequest());
    }

}
