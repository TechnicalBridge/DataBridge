package com.tbridge.ai.controller;

import com.tbridge.ai.dto.response.Salud;
import com.tbridge.ai.service.LlmService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** GET /api/health: si el asistente esta arriba y si tiene un LLM. */
@RestController
@Tag(name = "Salud")
public class SaludController {

    private final LlmService llm;

    public SaludController(LlmService llm) {
        this.llm = llm;
    }

    @GetMapping("/api/health")
    @Operation(summary = "Estado del asistente")
    public Salud salud() {
        return new Salud(true, "ms-ai", llm.configurado(), llm.estado());
    }
}
