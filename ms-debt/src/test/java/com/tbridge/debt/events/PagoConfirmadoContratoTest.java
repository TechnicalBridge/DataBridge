package com.tbridge.debt.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.debt.config.RabbitConfig;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * El aviso de pago, contra el contrato que ms-debt comparte con ms-payments
 * (src/test/resources/contratos/pago-confirmado.json, con una copia igual en ms-payments). ms-debt lo lee: escucha en la cola que
 * dice el contrato y entiende cada campo del ejemplo. La misma prueba, del
 * lado que lo escribe, vive en ms-payments.
 */
class PagoConfirmadoContratoTest {

    private static JsonNode contrato() throws IOException {
        return new ObjectMapper().readTree(Path.of("src", "test", "resources", "contratos", "pago-confirmado.json").toFile());
    }

    @Test
    void escucha_donde_publica_ms_payments() throws IOException {
        JsonNode transporte = contrato().get("transporte");

        assertEquals(transporte.get("exchange").asText(), PagoConfirmado.EXCHANGE);
        assertEquals(transporte.get("routing_key").asText(), PagoConfirmado.ROUTING_KEY);
        assertEquals(transporte.get("cola").asText(), PagoConfirmado.QUEUE);
    }

    @Test
    void entiende_cada_campo_del_ejemplo() throws IOException {
        byte[] cuerpo = new ObjectMapper().writeValueAsBytes(contrato().get("ejemplo"));
        MessageProperties propiedades = new MessageProperties();
        propiedades.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        //  Lo que hace el listener: la clase sale de su parametro, no del mensaje.
        propiedades.setInferredArgumentType(PagoConfirmado.class);

        PagoConfirmado aviso = (PagoConfirmado) new RabbitConfig().jackson2JsonMessageConverter()
                .fromMessage(new Message(cuerpo, propiedades));

        assertEquals(new PagoConfirmado(PagoConfirmado.TIPO, 41L, 3L, 7L, "16482337-7", "76418902-7",
                new BigDecimal("309400"), "CLP", 309400L, new BigDecimal("39485.65"), "khipu", "kh-ejemplo-0001",
                Instant.parse("2026-10-06T15:30:00Z"), List.of(7L, 8L), new BigDecimal("9400"),
                new BigDecimal("9400")), aviso);
    }

    @Test
    void un_mensaje_no_elige_la_clase_que_se_crea() throws IOException {
        byte[] cuerpo = new ObjectMapper().writeValueAsBytes(contrato().get("ejemplo"));
        MessageProperties propiedades = new MessageProperties();
        propiedades.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        propiedades.setInferredArgumentType(PagoConfirmado.class);
        //  Una cabecera que pide otra clase: se ignora.
        propiedades.setHeader("__TypeId__", "java.util.HashMap");

        Object aviso = new RabbitConfig().jackson2JsonMessageConverter().fromMessage(new Message(cuerpo, propiedades));

        assertEquals(PagoConfirmado.class, aviso.getClass());
    }
}
