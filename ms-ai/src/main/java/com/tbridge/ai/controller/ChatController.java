package com.tbridge.ai.controller;

import com.tbridge.ai.config.OpenApiConfig;
import com.tbridge.ai.dto.request.Pregunta;
import com.tbridge.ai.dto.response.Respuesta;
import com.tbridge.ai.exception.ApiError;
import com.tbridge.ai.service.AsistenteService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/** POST /api/ai/chat: una pregunta del deudor sobre sus deudas. */
@RestController
@RequestMapping("/api/ai")
@Tag(name = "Asistente")
public class ChatController {

    private final AsistenteService asistente;

    public ChatController(AsistenteService asistente) {
        this.asistente = asistente;
    }

    @PostMapping("/chat")
    @Operation(summary = "Preguntarle al asistente",
            description = """
                    Responde sobre las deudas del deudor de la sesion: cuánto debe, a quién, cómo pagar en cuotas. \
                    Solo lee: no modifica ninguna deuda. Detecta frustración y desconfianza y ajusta el tono. \
                    Con un LLM configurado responde con él; si no, con reglas locales.""")
    @SecurityRequirement(name = OpenApiConfig.JWT)
    @ApiResponse(responseCode = "200", description = "La respuesta del asistente")
    @ApiResponse(responseCode = "401", description = "Sin sesion",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "Rol o permisos no autorizados")
    @ApiResponse(responseCode = "404", description = "Deudor no registrado")
    @ApiResponse(responseCode = "502", description = "Protocolo de deuda invalido")
    @ApiResponse(responseCode = "503", description = "Dependencia temporalmente no disponible")
    public Respuesta chat(@RequestBody Pregunta pregunta,
                          @Parameter(hidden = true)
                          @org.springframework.security.core.annotation.AuthenticationPrincipal com.tbridge.ai.security.JwtPrincipal principal,
                          org.springframework.security.core.Authentication authentication) {
        if (!principal.isDebtor()) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No tienes permiso para esto");
        return asistente.responder(pregunta, "Bearer " + authentication.getCredentials());
    }
}
