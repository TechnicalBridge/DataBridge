package com.tbridge.ai.controller;

import com.tbridge.ai.dto.response.Salud;
import com.tbridge.ai.service.LlmService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/ai/salud: si el asistente esta arriba y si tiene un LLM. Va bajo
 * /api/ai, como todo lo del asistente, porque es lo unico que el gateway le
 * manda a ms-ai: en /api/health Swagger lo mostraba y respondia 404.
 */
@RestController
@Tag(name = "Salud")
public class SaludController {

    private final LlmService llm;

    public SaludController(LlmService llm) {
        this.llm = llm;
    }

    @GetMapping("/api/ai/salud")
    @Operation(summary = "Estado del asistente")
    public Salud salud() {
        return new Salud(true, "ms-ai", llm.configurado(), llm.estado());
    }
}
