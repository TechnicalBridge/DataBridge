package com.tbridge.payments.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.payments.config.RabbitConfig;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageProperties;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * El aviso de pago, contra el contrato que ms-payments comparte con ms-debt
 * (src/test/resources/contratos/pago-confirmado.json, con una copia igual en ms-debt). ms-payments lo escribe: lo que manda
 * tiene que ser exactamente el ejemplo, y por el camino que escucha ms-debt.
 * La misma prueba, del lado que lo lee, vive en ms-debt.
 */
class PagoConfirmadoContratoTest {

    private static JsonNode contrato() throws IOException {
        return new ObjectMapper().readTree(Path.of("src", "test", "resources", "contratos", "pago-confirmado.json").toFile());
    }

    @Test
    void viaja_por_el_camino_que_escucha_ms_debt() throws IOException {
        JsonNode transporte = contrato().get("transporte");

        assertEquals(transporte.get("exchange").asText(), PagoConfirmado.EXCHANGE);
        assertEquals(transporte.get("routing_key").asText(), PagoConfirmado.ROUTING_KEY);
        assertEquals(transporte.get("cola").asText(), PagoConfirmado.QUEUE);
    }

    @Test
    void lo_que_manda_es_el_ejemplo_del_contrato() throws IOException {
        PagoConfirmado aviso = new PagoConfirmado(PagoConfirmado.TIPO, 41L, 3L, 7L, "16482337-7", "76418902-7",
                new BigDecimal("309400"), "CLP", 309400L, new BigDecimal("39485.65"), "khipu", "kh-ejemplo-0001",
                Instant.parse("2026-10-06T15:30:00Z"), List.of(7L, 8L), new BigDecimal("9400"),
                new BigDecimal("9400"));

        //  Con el convertidor de verdad, el que usa para publicar en RabbitMQ.
        byte[] enviado = new RabbitConfig().jackson2JsonMessageConverter()
                .toMessage(aviso, new MessageProperties())
                .getBody();

        assertEquals(contrato().get("ejemplo"), new ObjectMapper().readTree(enviado));
    }
}
