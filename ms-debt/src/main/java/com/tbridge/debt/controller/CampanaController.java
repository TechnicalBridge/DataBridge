package com.tbridge.debt.controller;

import com.tbridge.debt.config.OpenApiConfig;
import com.tbridge.debt.dto.request.CampanaRequest;
import com.tbridge.debt.dto.request.EstadoCampanaRequest;
import com.tbridge.debt.dto.response.CampanaPortalResponse;
import com.tbridge.debt.exception.ApiError;
import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.exception.ErrorContrato;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.security.JwtPrincipal;
import com.tbridge.debt.service.CampanaService;
import com.tbridge.debt.service.DebtService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.EntityModel;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;

/**
 * Las campanas desde el portal de empresas: lo mismo que el contrato
 * (POST /api/v1/campanas), para la empresa que no tiene un sistema propio.
 */
@RestController
@RequestMapping("/api/debts/campanas")
@Tag(name = "Campanas", description = "Crear, cambiar, pausar y terminar las campanas desde el portal")
@SecurityRequirement(name = OpenApiConfig.JWT)
public class CampanaController {

    private final CampanaService campanas;
    private final DebtService debts;

    public CampanaController(CampanaService campanas, DebtService debts) {
        this.campanas = campanas;
        this.debts = debts;
    }

    @GetMapping
    @Operation(summary = "Las campanas de la empresa",
            description = "Las que gestiona: las de los acreedores que le dieron mandato y, si cobra sin agencia, "
                    + "las suyas. Con su avance. Van en `_embedded.campanas`.")
    @ApiResponse(responseCode = "200", description = "Las campanas")
    @ApiResponse(responseCode = "403", description = "Quien pregunta no es una empresa",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public CollectionModel<EntityModel<CampanaPortalResponse>> listar(
            @Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal user) {
        return CollectionModel.of(campanas.listar(empresa(user)).stream().map(CampanaController::conEnlaces).toList(),
                linkTo(CampanaController.class).withSelfRel());
    }

    @PostMapping
    @Operation(summary = "Crear o cambiar una campana",
            description = "Los mismos campos y reglas que el contrato. Sin `id_externo`, DataBridge le pone uno.")
    @ApiResponse(responseCode = "200", description = "La campana")
    @ApiResponse(responseCode = "400", description = "Una cadencia, unos intentos o unas fechas que no sirven",
            content = @Content(schema = @Schema(implementation = ErrorContrato.class)))
    @ApiResponse(responseCode = "403", description = "Un acreedor sin mandato vigente de la empresa",
            content = @Content(schema = @Schema(implementation = ErrorContrato.class)))
    public EntityModel<CampanaPortalResponse> guardar(
            @Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal user,
            @RequestBody CampanaRequest pedido) {
        return conEnlaces(campanas.guardar(empresa(user), pedido));
    }

    @PostMapping("/{idExterno}/estado")
    @Operation(summary = "Pausar, reanudar o terminar una campana",
            description = "Solo el estado: el nombre, las fechas y la cadencia quedan como estaban.")
    @ApiResponse(responseCode = "200", description = "La campana, con su estado nuevo")
    @ApiResponse(responseCode = "404", description = "No es una campana de la empresa",
            content = @Content(schema = @Schema(implementation = ErrorContrato.class)))
    public EntityModel<CampanaPortalResponse> estado(
            @Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal user,
            @PathVariable String idExterno, @Valid @RequestBody EstadoCampanaRequest pedido) {
        return conEnlaces(campanas.cambiarEstado(empresa(user), idExterno, pedido.estado().trim()));
    }

    private Organization empresa(JwtPrincipal user) {
        if (user == null || !user.isCreditor()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Las campañas son de las empresas");
        }
        return debts.organizacionDe(user);
    }

    /** Lo que se puede hacer con cada una, segun su estado. Una terminada ya no cambia. */
    private static EntityModel<CampanaPortalResponse> conEnlaces(CampanaPortalResponse campana) {
        EntityModel<CampanaPortalResponse> modelo = EntityModel.of(campana);
        var estado = linkTo(CampanaController.class).slash(campana.idExterno()).slash("estado");
        switch (campana.estado()) {
            case "en_curso" -> modelo.add(estado.withRel("pausar"), estado.withRel("terminar"));
            case "pausada" -> modelo.add(estado.withRel("reanudar"), estado.withRel("terminar"));
            default -> { }
        }
        return modelo;
    }
}
