package com.tbridge.debt.controller;

import com.tbridge.debt.exception.ApiError;
import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.security.JwtPrincipal;
import com.tbridge.debt.config.OpenApiConfig;
import com.tbridge.debt.dto.request.ClaveRequest;
import com.tbridge.debt.dto.response.ClaveEmitidaResponse;
import com.tbridge.debt.dto.response.ClaveResponse;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.service.ApiKeyService;
import com.tbridge.debt.service.DebtService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.EntityModel;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;

/**
 * Las claves de API de la empresa, desde el portal.
 *
 * <p>Antes una clave se pedia por dentro, con la clave interna: habia que
 * pedirsela a quien administra DataBridge. Aca la empresa emite las suyas y
 * revoca la que se filtro, sin esperar a nadie. La clave se ve una sola vez.
 *
 * <p>Va y viene con la entrega por API ({@code INTEGRACION_API}): sin ella, una
 * clave no sirve para nada, y /api/claves responde 404.
 */
@RestController
@ConditionalOnProperty(name = "app.integracion.api", havingValue = "true")
@RequestMapping("/api/claves")
@Tag(name = "Claves de API", description = "Emitir y revocar las claves del contrato de integracion")
@SecurityRequirement(name = OpenApiConfig.JWT)
public class ClaveController {

    private final ApiKeyService claves;
    private final DebtService debts;

    public ClaveController(ApiKeyService claves, DebtService debts) {
        this.claves = claves;
        this.debts = debts;
    }

    @GetMapping
    @Operation(summary = "Las claves de la empresa",
            description = "Las activas y las revocadas, sin la clave: de ella solo queda el prefijo. Van en `_embedded.claves`.")
    @ApiResponse(responseCode = "200", description = "Las claves")
    @ApiResponse(responseCode = "403", description = "Quien pregunta no es una empresa",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public CollectionModel<EntityModel<ClaveResponse>> listar(
            @Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal user) {
        return CollectionModel.of(claves.listar(organizacion(user)).stream().map(ClaveController::conEnlaces).toList(),
                linkTo(ClaveController.class).withSelfRel());
    }

    @PostMapping
    @Operation(summary = "Emitir una clave",
            description = "Se devuelve una sola vez: en la base queda solo su huella. Si se pierde, se emite otra y se revoca esta.")
    @ApiResponse(responseCode = "200", description = "La clave, para guardarla ahora")
    @ApiResponse(responseCode = "400", description = "Falta el nombre",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    @ApiResponse(responseCode = "403", description = "Quien pide no es una empresa",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ClaveEmitidaResponse emitir(@Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal user,
                                       @Valid @RequestBody ClaveRequest pedido) {
        return claves.emitir(organizacion(user), pedido.nombre().trim());
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Revocar una clave",
            description = "Deja de servir en el acto. No se borra: queda para saber que existio y hasta cuando se uso.")
    @ApiResponse(responseCode = "200", description = "La clave, revocada")
    @ApiResponse(responseCode = "404", description = "No es de la empresa de la sesion",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ClaveResponse revocar(@Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal user,
                                 @PathVariable Long id) {
        return claves.revocar(organizacion(user), id);
    }

    private Organization organizacion(JwtPrincipal user) {
        if (user == null || !user.isCreditor()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Las claves de API son de las empresas");
        }
        return debts.organizacionDe(user);
    }

    private static EntityModel<ClaveResponse> conEnlaces(ClaveResponse clave) {
        EntityModel<ClaveResponse> modelo = EntityModel.of(clave);
        if (clave.activa()) {
            modelo.add(linkTo(ClaveController.class).slash(clave.id()).withRel("revocar"));
        }
        return modelo;
    }
}
