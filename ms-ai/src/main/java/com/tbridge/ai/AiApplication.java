package com.tbridge.ai;

import com.tbridge.ai.config.LlmProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * ms-ai: el asistente del portal.
 *
 * <p>No tiene base de datos propia: consulta las deudas a ms-debt con la sesion
 * del deudor, asi que no puede ver nada que el deudor no pueda ver. Responde
 * con una cascada de LLMs si hay alguno configurado y, si no, con su motor local
 * de reglas.
 */
@SpringBootApplication
@EnableConfigurationProperties(LlmProperties.class)
public class AiApplication {

    public static void main(String[] args) {
        SpringApplication.run(AiApplication.class, args);
    }
}
