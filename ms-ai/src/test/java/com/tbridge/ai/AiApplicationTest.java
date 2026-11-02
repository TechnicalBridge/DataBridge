package com.tbridge.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** El servicio arranca solo, sin base ni LLM, y publica lo que leen Docker y el gateway. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"app.llm.provider=local", "app.debt-url=http://ms-debt.invalid"})
@org.springframework.context.annotation.Import(com.tbridge.ai.security.JwtTestConfig.class)
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
        assertTrue(documentacion.contains("/api/ai/salud"), documentacion);
    }

    /** Swagger solo ofrece lo que el gateway le manda a ms-ai (/api/ai/**), y por la misma direccion. */
    @Test
    void cada_operacion_de_swagger_se_puede_llamar_por_el_gateway() throws Exception {
        JsonNode documentacion = new ObjectMapper().readTree(http.getForObject("/v3/api-docs/portal", String.class));
        documentacion.get("paths").fieldNames()
                .forEachRemaining(ruta -> assertTrue(ruta.startsWith("/api/ai/"), ruta));
        assertEquals("/", documentacion.at("/servers/0/url").asText());
        assertEquals("El portal, por el gateway", documentacion.at("/servers/0/description").asText());
    }
}
