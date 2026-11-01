package com.tbridge.debt.controller;

import com.tbridge.debt.config.OpenApiConfig;
import com.tbridge.debt.dto.request.MandatoPortalRequest;
import com.tbridge.debt.dto.request.RenovarMandatoRequest;
import com.tbridge.debt.dto.response.MandatoPortalResponse;
import com.tbridge.debt.dto.response.MandatosResponse;
import com.tbridge.debt.exception.ApiError;
import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.security.JwtPrincipal;
import com.tbridge.debt.service.DebtService;
import com.tbridge.debt.service.MandatoPortalService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Los mandatos desde el portal de la agencia (#234): con la API apagada, es por
 * donde nace un mandato. Lo de una empresa que cobra lo suyo no cambia.
 */
@RestController
@RequestMapping("/api/debts/mandatos")
@Tag(name = "Mandatos", description = "La agencia declara, renueva y termina sus mandatos desde el portal")
@SecurityRequirement(name = OpenApiConfig.JWT)
public class MandatoController {

    private final MandatoPortalService mandatos;
    private final DebtService debts;

    public MandatoController(MandatoPortalService mandatos, DebtService debts) {
        this.mandatos = mandatos;
        this.debts = debts;
    }

    @GetMapping
    @Operation(summary = "Los mandatos de la empresa",
            description = "Con su estado y las deudas que siguen en gestion. Una empresa que cobra lo suyo, sin "
                    + "agencia, recibe `agencia: false` y ninguno.")
    @ApiResponse(responseCode = "200", description = "Los mandatos")
    @ApiResponse(responseCode = "403", description = "Quien pregunta no es una empresa",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public MandatosResponse listar(@Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal user) {
        return mandatos.listar(empresa(user));
    }

    @PostMapping
    @Operation(summary = "Declarar un mandato",
            description = "La agencia declara que cobra por cuenta de un acreedor, y responde por eso. Si DataBridge "
                    + "no conoce al acreedor, lo registra con su razon social. Queda quien lo declaro.")
    @ApiResponse(responseCode = "201", description = "El mandato")
    @ApiResponse(responseCode = "400", description = "Un RUT, unas fechas, una mora o un descuento que no sirven",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "La empresa no es una agencia",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Ya tiene un mandato con ese acreedor",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<MandatoPortalResponse> declarar(
            @Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal user,
            @Valid @RequestBody MandatoPortalRequest pedido) {
        return ResponseEntity.status(HttpStatus.CREATED).body(mandatos.declarar(empresa(user), user.email(), pedido));
    }

    @PostMapping("/{id}/renovar")
    @Operation(summary = "Cambiarle la fecha de termino",
            description = "De hoy en adelante, o ninguna. Uno vencido vuelve a estar vigente; uno terminado, no.")
    @ApiResponse(responseCode = "200", description = "El mandato")
    @ApiResponse(responseCode = "404", description = "No es un mandato de la empresa",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Ya termino",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public MandatoPortalResponse renovar(
            @Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal user,
            @PathVariable Long id, @RequestBody(required = false) RenovarMandatoRequest pedido) {
        return mandatos.renovar(empresa(user), user.email(), id, pedido);
    }

    @PostMapping("/{id}/terminar")
    @Operation(summary = "Terminar un mandato",
            description = "Desde ya no se carga cartera ni se crean campanas para ese acreedor. Lo que ya se cargo "
                    + "se sigue cobrando hasta pagarse.")
    @ApiResponse(responseCode = "200", description = "El mandato, terminado")
    @ApiResponse(responseCode = "404", description = "No es un mandato de la empresa",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "409", description = "Ya estaba terminado",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public MandatoPortalResponse terminar(
            @Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal user, @PathVariable Long id) {
        return mandatos.terminar(empresa(user), user.email(), id);
    }

    private Organization empresa(JwtPrincipal user) {
        if (user == null || !user.isCreditor()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Los mandatos son de las empresas");
        }
        return debts.organizacionDe(user);
    }
}
