package com.tbridge.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;
import java.util.Map;

/**
 * La cascada de LLMs: varios proveedores compatibles con la API de OpenAI,
 * probados en el orden de {@code orden}, con relevo al siguiente si uno falla o
 * agota su cuota.
 *
 * <p>Ollama va primero por omision: si corre en el equipo, ninguna deuda sale
 * de ahi. Groq y Gemini entran solo con su llave. Sin ningun proveedor usable,
 * el asistente responde con su motor local de reglas.
 */
@ConfigurationProperties(prefix = "app.llm")
public record LlmProperties(
        List<String> orden,
        Integer enfriamientoMinutos,
        Map<String, Proveedor> proveedores) {

    public LlmProperties {
        orden = orden == null ? List.of() : orden;
        proveedores = proveedores == null ? Map.of() : proveedores;
        //  Cuantos minutos se salta un proveedor que fallo o agoto su cuota. 0 = sin memoria.
        enfriamientoMinutos = enfriamientoMinutos == null || enfriamientoMinutos < 0 ? 0 : enfriamientoMinutos;
    }

    /** Un proveedor de la cascada, tal como llega de la configuracion. */
    public record Proveedor(
            String apiKey,
            String baseUrl,
            String model,
            Integer timeoutSegundos,
            Boolean requiereLlave) {

        /** Ollama no pide llave; los demas si. Por omision, si. */
        public boolean conLlave() {
            return requiereLlave == null || requiereLlave;
        }

        /** Cuanto esperar su respuesta. Por omision, un minuto. */
        public int timeout() {
            return timeoutSegundos == null || timeoutSegundos <= 0 ? 60 : timeoutSegundos;
        }
    }
}
