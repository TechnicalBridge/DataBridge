package com.tbridge.auth.service;

import com.tbridge.auth.exception.ApiException;
import org.junit.jupiter.api.Test;
import java.time.*;
import static org.junit.jupiter.api.Assertions.*;

@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class IdentifierLimitTest {
    static class Time extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
        public Instant instant() { return now; }
        void advance(long seconds) { now = now.plusSeconds(seconds); }
    }
    @Test void normalizaAislaExpiraYEscalaSinSleep(org.springframework.boot.test.system.CapturedOutput output) {
        Time clock = new Time();
        IdentifierLimit limit = new IdentifierLimit(2, 60, 900, clock);
        limit.check("access", null, " Person@example.invalid ");
        limit.check("access", null, "person@example.invalid");
        assertThrows(ApiException.class, () -> limit.check("access", null, "PERSON@example.invalid"));
        assertDoesNotThrow(() -> limit.check("access", null, "other@example.invalid"));
        assertDoesNotThrow(() -> limit.check("delivery", null, "person@example.invalid"));
        clock.advance(60);
        limit.check("access", null, "person@example.invalid");
        limit.check("access", null, "person@example.invalid");
        clock.advance(60);
        assertThrows(ApiException.class, () -> limit.check("access", null, "person@example.invalid"));
        clock.advance(60);
        assertDoesNotThrow(() -> limit.check("access", null, "person@example.invalid"));
        assertFalse(output.toString().contains("person@example.invalid"));
    }
    @Test void rutFormateadoComparteCupo() {
        IdentifierLimit limit = new IdentifierLimit(1,60,900,new Time());
        limit.check("access", "16.482.337-7", null);
        assertThrows(ApiException.class, () -> limit.check("access","16482337-7",null));
    }
    @Test void configuracionInvalidaFallaAlArrancar() {
        assertThrows(IllegalArgumentException.class, () -> new IdentifierLimit(0,60,900,new Time()));
        assertThrows(IllegalArgumentException.class, () -> new IdentifierLimit(1,60,1,new Time()));
    }
}
