package com.tbridge.debt.service;

import com.tbridge.debt.model.OutboxEvent;
import com.tbridge.debt.model.Subscription;
import com.tbridge.debt.repository.OutboxEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * Saca los eventos de la bandeja y los entrega firmados.
 *
 * <p>La firma es la del contrato (seccion 8.1): HMAC-SHA256 del timestamp,
 * un punto y el cuerpo exacto que viaja. El receptor la recalcula y descarta
 * lo que no calce o lo que tenga mas de 5 minutos, asi que un evento
 * interceptado no se puede alterar ni volver a mandar mas tarde.
 *
 * <p>Entrega "al menos una vez": si el receptor guardo el evento pero la
 * respuesta se perdio, lo va a recibir de nuevo, y por eso deduplica por id.
 */
@Service
public class EventDispatcher {

    private static final Logger log = LoggerFactory.getLogger(EventDispatcher.class);

    private final OutboxEventRepository bandeja;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public EventDispatcher(OutboxEventRepository bandeja) {
        this.bandeja = bandeja;
    }

    @Scheduled(fixedDelayString = "${app.eventos.intervalo-ms:15000}")
    @Transactional
    public void despachar() {
        for (OutboxEvent evento : bandeja.findTop100ByStatusAndNextAttemptAtBeforeAndSubscriptionActiveTrueOrderByIdAsc(
                OutboxEvent.Status.pending, Instant.now())) {
            try {
                entregar(evento);
                evento.entregado();
                log.info("Evento {} {} entregado a {}", evento.getType(), evento.getEventId(),
                        evento.getSubscription().getUrl());
            } catch (InterruptedException corte) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception fallo) {
                evento.fallo(fallo.getMessage() == null ? fallo.getClass().getSimpleName() : fallo.getMessage());
                log.warn("No se pudo entregar el evento {} (intento {}): {}",
                        evento.getEventId(), evento.getAttempts(), evento.getLastError());
            }
            bandeja.save(evento);
        }
    }

    private void entregar(OutboxEvent evento) throws Exception {
        Subscription destino = evento.getSubscription();
        String cuerpo = evento.getPayload();
        long marca = Instant.now().getEpochSecond();

        HttpRequest peticion = HttpRequest.newBuilder(URI.create(destino.getUrl()))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-Evento", evento.getType())
                .header("X-Evento-Id", "evt_" + evento.getEventId())
                .header("X-Timestamp", String.valueOf(marca))
                .header("X-Firma", firma(destino.getSecret(), marca, cuerpo))
                .POST(HttpRequest.BodyPublishers.ofString(cuerpo, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> respuesta = http.send(peticion, HttpResponse.BodyHandlers.ofString());
        if (respuesta.statusCode() / 100 != 2) {
            String detalle = respuesta.body() == null ? "" : respuesta.body();
            throw new IllegalStateException("HTTP " + respuesta.statusCode() + ": "
                    + detalle.substring(0, Math.min(detalle.length(), 200)));
        }
    }

    /** {@code v1=} + HMAC-SHA256(secreto, timestamp + "." + cuerpo) en hex. */
    public static String firma(String secreto, long marca, String cuerpo) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secreto.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] hmac = mac.doFinal((marca + "." + cuerpo).getBytes(StandardCharsets.UTF_8));
            return "v1=" + HexFormat.of().formatHex(hmac);
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo firmar el evento", e);
        }
    }
}
