package com.tbridge.auth.controller;

import com.tbridge.auth.config.OpenApiConfig;
import com.tbridge.auth.dto.response.EstadoResponse;
import com.tbridge.auth.exception.ApiError;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lo que el panel de administracion de DataBridge le pide a ms-auth.
 *
 * <p>Todo lo que cuelga de {@code /api/admin} es solo para el admin de
 * DataBridge: lo exige {@code SecurityConfig}, no cada metodo, para que un
 * endpoint nuevo no pueda quedar abierto por olvido.
 */
@RestController
@RequestMapping("/api/admin/auth")
@Tag(name = "Panel de administracion", description = "Solo para el admin de DataBridge")
@SecurityRequirement(name = OpenApiConfig.JWT)
public class AdminController {

    @GetMapping("/estado")
    @Operation(summary = "Si ms-auth responde",
            description = "La portada del panel lo llama para mostrar que servicios estan arriba.")
    @ApiResponse(responseCode = "200", description = "El servicio responde")
    @ApiResponse(responseCode = "401", description = "Sin sesion",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "La sesion no es de un admin de DataBridge",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public EstadoResponse estado() {
        return new EstadoResponse("ms-auth", true);
    }
}
