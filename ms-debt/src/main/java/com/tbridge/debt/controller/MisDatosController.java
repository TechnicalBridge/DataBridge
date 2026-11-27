package com.tbridge.debt.controller;

import com.tbridge.debt.exception.ApiError;
import com.tbridge.debt.security.JwtPrincipal;
import com.tbridge.debt.config.OpenApiConfig;
import com.tbridge.debt.dto.request.MisDatosRequest;
import com.tbridge.debt.dto.response.MisDatosResponse;
import com.tbridge.debt.service.MisDatosService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Los datos del deudor y sus preferencias; el contacto tiene operaciones separadas. */
@RestController
@RequestMapping("/api/debts/mis-datos")
@Tag(name = "Mis datos", description = "Los datos del deudor que trajo el acreedor")
@SecurityRequirement(name = OpenApiConfig.JWT)
public class MisDatosController {

    private final MisDatosService datos;

    public MisDatosController(MisDatosService datos) {
        this.datos = datos;
    }

    @GetMapping
    @Operation(summary = "Los datos del deudor",
            description = """
                    Solo el deudor. Nombre, RUT, el correo y el telefono a medias, a quien le debe y quien cobra \
                    por su cuenta. Sirve para confirmar que la cobranza es legitima.""")
    @ApiResponse(responseCode = "200", description = "Sus datos")
    @ApiResponse(responseCode = "403", description = "Quien pregunta es una empresa",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public MisDatosResponse ver(@Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal user) {
        return datos.ver(user);
    }

    @PatchMapping
    @Operation(summary = "Encender o apagar los recordatorios",
            description = "Actualiza exclusivamente las preferencias de recordatorios. El contacto tiene operaciones separadas.")
    @ApiResponse(responseCode = "200", description = "Sus datos, con el cambio")
    @ApiResponse(responseCode = "400", description = "Falta decir si quiere los recordatorios",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public MisDatosResponse cambiar(@Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal user,
                                    @Valid @RequestBody MisDatosRequest pedido) {
        return datos.cambiar(user, pedido);
    }
}
