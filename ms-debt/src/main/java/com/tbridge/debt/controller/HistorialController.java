package com.tbridge.debt.controller;

import com.tbridge.debt.exception.ApiError;
import com.tbridge.debt.security.JwtPrincipal;
import com.tbridge.debt.config.OpenApiConfig;
import com.tbridge.debt.dto.response.PagoResponse;
import com.tbridge.debt.service.ComprobanteService;
import com.tbridge.debt.service.HistorialService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.hateoas.CollectionModel;
import org.springframework.hateoas.EntityModel;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import static org.springframework.hateoas.server.mvc.WebMvcLinkBuilder.linkTo;

/**
 * Los pagos ya abonados: el historial del deudor, los pagos recibidos de la
 * empresa, y el comprobante de cada uno.
 */
@RestController
@RequestMapping("/api/debts/pagos")
@Tag(name = "Pagos", description = "El historial de pagos y el comprobante de cada uno")
@SecurityRequirement(name = OpenApiConfig.JWT)
public class HistorialController {

    private final HistorialService historial;
    private final ComprobanteService comprobantes;

    public HistorialController(HistorialService historial, ComprobanteService comprobantes) {
        this.historial = historial;
        this.comprobantes = comprobantes;
    }

    @GetMapping
    @Operation(summary = "Los pagos de quien pregunta",
            description = """
                    Al deudor, los que abono a sus deudas; a la empresa, los que entraron a su cartera. Del mas \
                    nuevo al mas viejo, hasta 300. Van en `_embedded.pagos`, cada uno con su comprobante.""")
    @ApiResponse(responseCode = "200", description = "Los pagos")
    @ApiResponse(responseCode = "401", description = "Sin sesion",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public CollectionModel<EntityModel<PagoResponse>> pagos(
            @Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal user) {
        return CollectionModel.of(historial.pagos(user).stream().map(HistorialController::conEnlaces).toList(),
                linkTo(HistorialController.class).withSelfRel());
    }

    @GetMapping(value = "/{id}/comprobante", produces = MediaType.APPLICATION_PDF_VALUE)
    @Operation(summary = "El comprobante de un pago (PDF)",
            description = "Que se pago, de que deuda, por donde y cuando. Lo descarga el deudor o la empresa que opera la deuda.")
    @ApiResponse(responseCode = "200", description = "El PDF", content = @Content(mediaType = MediaType.APPLICATION_PDF_VALUE))
    @ApiResponse(responseCode = "403", description = "Pago inexistente o no visible",
            content = @Content(mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<byte[]> comprobante(@Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal user,
                                              @PathVariable Long id) {
        byte[] pdf = comprobantes.generar(user, id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"comprobante-pago-" + id + ".pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }

    private static EntityModel<PagoResponse> conEnlaces(PagoResponse pago) {
        return EntityModel.of(pago,
                linkTo(HistorialController.class).slash(pago.id()).slash("comprobante").withRel("comprobante"),
                linkTo(DebtController.class).slash(pago.deudaId()).withRel("deuda"));
    }
}
