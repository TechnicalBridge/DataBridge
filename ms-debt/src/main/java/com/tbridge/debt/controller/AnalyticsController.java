package com.tbridge.debt.controller;

import com.tbridge.debt.exception.ApiError;
import com.tbridge.debt.security.JwtPrincipal;
import com.tbridge.debt.config.OpenApiConfig;
import com.tbridge.debt.dto.response.ComisionResponse;
import com.tbridge.debt.dto.response.ResumenResponse;
import com.tbridge.debt.service.AnalyticsService;
import com.tbridge.debt.service.ComisionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.hateoas.EntityModel;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.YearMonth;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;
import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.methodOn;

@RestController
@Tag(name = "Analitica", description = "Los numeros de la cartera de la empresa")
@SecurityRequirement(name = OpenApiConfig.JWT)
public class AnalyticsController {

    private final AnalyticsService analytics;
    private final ComisionService comisiones;

    public AnalyticsController(AnalyticsService analytics, ComisionService comisiones) {
        this.analytics = analytics;
        this.comisiones = comisiones;
    }

    @GetMapping("/api/analytics/comision")
    @Operation(summary = "Lo que mi empresa le paga a DataBridge en un mes",
            description = "El modelo de negocio (#200): G es lo recuperado en el mes, en pesos, de las deudas que "
                    + "cargo la empresa; p(G) es 6, 10, 12 o 15 % segun el tramo; la comision es P(G) = G * p(G) y "
                    + "a la empresa le queda G - P(G). Con lo de cada campana; el tramo lo decide el total del mes.")
    @ApiResponse(responseCode = "200", description = "La comision del mes")
    @ApiResponse(responseCode = "403", description = "Quien pregunta no es una empresa",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ComisionResponse comision(
            @Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal user,
            @Parameter(description = "El mes, como 2026-10. Sin el, el de hoy", example = "2026-10")
            @RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth mes) {
        return comisiones.deLaEmpresa(user, mes);
    }

    @GetMapping("/api/analytics/summary")
    @Operation(summary = "El resumen de mi cartera",
            description = "Solo la empresa, y solo de su cartera: saldo y recuperado por moneda y por acreedor, y lo recuperado cada dia del ultimo mes.")
    @ApiResponse(responseCode = "200", description = "El resumen")
    @ApiResponse(responseCode = "403", description = "Quien pregunta no es una empresa registrada",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public EntityModel<ResumenResponse> summary(@Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal user) {
        return EntityModel.of(analytics.resumenPara(user),
                linkTo(methodOn(AnalyticsController.class).summary(null)).withSelfRel(),
                linkTo(methodOn(DebtController.class).list(null)).withRel("deudas"));
    }
}
