package com.tbridge.ai.service;

import com.tbridge.ai.client.DeudasClient;
import com.tbridge.ai.dto.request.Pregunta;
import com.tbridge.ai.dto.response.Animo;
import com.tbridge.ai.dto.response.Respuesta;
import com.tbridge.ai.util.Valor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Una pregunta del deudor: sus deudas, su animo y la respuesta, del LLM o de las reglas. */
@Service
public class AsistenteService {

    private final DeudasClient deudas;
    private final LlmService llm;

    public AsistenteService(DeudasClient deudas, LlmService llm) {
        this.deudas = deudas;
        this.llm = llm;
    }

    public Respuesta responder(Pregunta pregunta, String authorization) {
        List<Map<String, Object>> susDeudas = deudas.deudasDelDeudor(authorization);

        List<Map<String, Object>> historia = new ArrayList<>();
        if (pregunta.messages() != null) {
            pregunta.messages().stream().filter(Objects::nonNull).forEach(historia::add);
        }
        if (Valor.presente(pregunta.message())) {
            historia.add(Map.of("role", "user", "content", pregunta.message()));
        }
        String ultimo = historia.reversed().stream()
                .filter(m -> "user".equals(m.get("role")))
                .findFirst()
                .map(m -> Valor.texto(Valor.primero(m.get("content"), m.get("message"))))
                .orElse("");

        Animo animo = MotorLocal.sentimiento(ultimo);
        Optional<String> delLlm = llm.responder(historia, susDeudas, animo.etiqueta());
        return new Respuesta(
                delLlm.orElseGet(() -> MotorLocal.responder(ultimo, susDeudas)),
                delLlm.isPresent() ? "spacexai" : "local-nlp",
                susDeudas.size(),
                animo);
    }
}
