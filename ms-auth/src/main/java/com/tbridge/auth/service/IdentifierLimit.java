package com.tbridge.auth.service;

import com.tbridge.auth.exception.ApiException;
import com.tbridge.auth.util.Hash;
import com.tbridge.auth.util.Rut;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;

/** Cuota por identidad antes de consultar si existe; independiente de los códigos emitidos. */
@Component
public class IdentifierLimit {
    private final int capacity;
    private final Duration window;
    private final Duration maxBlock;
    private final Clock clock;
    private final Map<String, State> states = new HashMap<>();
    private static final int MAX_IDENTITIES = 100000;
    private record State(int count, int strikes, Instant until) {}

    @org.springframework.beans.factory.annotation.Autowired
    public IdentifierLimit(@Value("${app.identifier-limit.capacity:10}") int capacity,
                           @Value("${app.identifier-limit.window-seconds:60}") long seconds,
                           @Value("${app.identifier-limit.max-block-seconds:900}") long maxSeconds) {
        this(capacity, seconds, maxSeconds, Clock.systemUTC());
    }

    IdentifierLimit(int capacity, long seconds, long maxSeconds, Clock clock) {
        if (capacity < 1 || seconds < 1 || maxSeconds < seconds || maxSeconds > 86400) throw new IllegalArgumentException("Política de identificador inválida");
        this.capacity = capacity;
        this.window = Duration.ofSeconds(seconds);
        this.maxBlock = Duration.ofSeconds(maxSeconds);
        this.clock = clock;
    }

    public synchronized void check(String operation, String rut, String email) {
        String identity = rut != null && !rut.isBlank() ? "rut:" + Rut.normalizar(rut)
                : "email:" + (email == null ? "" : email.trim().toLowerCase(Locale.ROOT));
        String key = operation + ":" + Hash.sha256(identity);
        Instant now = clock.instant();
        State old = states.get(key);
        // Tras un período sin actividad se olvida también la reincidencia.
        if (old != null && !now.isBefore(old.until().plus(maxBlock))) old = null;
        if (old != null && old.count() >= capacity && now.isBefore(old.until())) reject();
        if (old == null || !now.isBefore(old.until())) {
            if (states.size() >= MAX_IDENTITIES) {
                states.entrySet().removeIf(e -> !now.isBefore(e.getValue().until().plus(maxBlock)));
                if (!states.containsKey(key) && states.size() >= MAX_IDENTITIES) reject();
            }
            old = new State(0, old == null ? 0 : old.strikes(), now.plus(window));
        }
        int count = old.count() + 1;
        int strikes = old.strikes();
        Instant until = old.until();
        if (count >= capacity) {
            strikes = Math.min(20, strikes + 1);
            long seconds = Math.min(maxBlock.toSeconds(), window.toSeconds() * (1L << (strikes - 1)));
            until = now.plusSeconds(seconds);
        }
        states.put(key, new State(count, strikes, until));
    }

    private static void reject() {
        LoggerFactory.getLogger(IdentifierLimit.class).info("firewall domain=AUTH rule=IDENTIFIER_RATE_LIMIT outcome=denied status=429");
        throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Demasiadas solicitudes. Intenta más tarde.");
    }
}
