package com.tbridge.ai.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/** El mensaje nuevo y la conversacion hasta ahora. */
public record Pregunta(
        @Schema(description = "Lo que escribio el deudor", example = "¿Cuánto debo?")
        @NotBlank @Size(max = 1000)
        String message,
        @Schema(description = "La conversacion anterior: [{role: user|assistant, content}]")
        @Size(max = 12)
        List<Map<String, Object>> messages) {

    @AssertTrue(message = "Cada mensaje anterior debe tener texto de hasta 1000 caracteres")
    public boolean isHistoryWithinLimits() {
        if (messages == null) return true;
        for (Map<String, Object> item : messages) {
            if (item == null) return false;
            Object content = item.containsKey("content") ? item.get("content") : item.get("message");
            if (!(content instanceof String text) || text.length() > 1000
                    || !(item.get("role") instanceof String role)
                    || (!role.equals("user") && !role.equals("assistant"))) return false;
        }
        return true;
    }
}
