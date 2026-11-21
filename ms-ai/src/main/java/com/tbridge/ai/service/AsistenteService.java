package com.tbridge.ai.service;

import com.tbridge.ai.client.DeudasClient;
import com.tbridge.ai.dto.request.Pregunta;
import com.tbridge.ai.dto.response.Animo;
import com.tbridge.ai.dto.response.Respuesta;
import com.tbridge.ai.security.ContencionDeInstrucciones;
import com.tbridge.ai.util.Valor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Una pregunta del deudor: sus deudas, su animo y la respuesta, del LLM o de las reglas. */
@Service
public class AsistenteService {

    private final DeudasClient deudas;
    private final LlmService llm;

    public AsistenteService(DeudasClient deudas, LlmService llm) {
        this.deudas = deudas;
        this.llm = llm;
    }

    public Respuesta responder(Pregunta pregunta, String authorization, String rutSesion) {
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
        Set<String> rutsPermitidos = ContencionDeInstrucciones.rutsPermitidos(rutSesion, susDeudas);

        //  La contención va después de pedir las deudas para conservar 401, 403,
        //  404 y 503, y antes del LLM para que el ataque ni siquiera se envíe.
        //  Solo se revisa el mensaje nuevo: un rechazo anterior no bloquea la
        //  conversación; ese ataque se quita del historial que verá el modelo.
        Optional<String> rechazo = ContencionDeInstrucciones.revisionDeEntrada(ultimo, rutsPermitidos);
        if (rechazo.isPresent()) {
            return new Respuesta(rechazo.get(), "local-nlp", susDeudas.size(), animo);
        }

        List<Map<String, Object>> historiaSegura =
                ContencionDeInstrucciones.historiaSinAtaques(historia, rutsPermitidos);
        Optional<String> delLlm = llm.responder(historiaSegura, susDeudas, animo.etiqueta());
        if (delLlm.isEmpty()) {
            return new Respuesta(MotorLocal.responder(ultimo, susDeudas), "local-nlp", susDeudas.size(), animo);
        }

        //  Si el LLM repite instrucciones, credenciales o un RUT ajeno, no se
        //  publica: se reemplaza por la negativa local y la fuente lo refleja.
        String respuesta = ContencionDeInstrucciones.protegerRespuesta(delLlm.get(), rutsPermitidos);
        boolean filtrada = !respuesta.equals(delLlm.get());
        return new Respuesta(respuesta, filtrada ? "local-nlp" : llm.proveedor(), susDeudas.size(), animo);
    }
}
