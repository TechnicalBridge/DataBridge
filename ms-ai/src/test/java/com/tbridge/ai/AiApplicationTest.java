package com.tbridge.ai;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** El servicio arranca solo, sin base ni LLM, y publica lo que leen Docker y el gateway. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.llm.orden=", "app.debt-url=http://ms-debt.invalid"})
class AiApplicationTest {

    @Autowired
    private TestRestTemplate http;

    @Test
    void el_healthcheck_de_docker_lo_ve_arriba() {
        assertEquals("{\"status\":\"UP\"}", http.getForObject("/actuator/health", String.class));
    }

    @Test
    void el_gateway_encuentra_su_documentacion_en_el_grupo_portal() {
        String documentacion = http.getForObject("/v3/api-docs/portal", String.class);
        assertTrue(documentacion.contains("/api/ai/chat"), documentacion);
        assertTrue(documentacion.contains("/api/health"), documentacion);
    }
}
