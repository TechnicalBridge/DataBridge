package com.tbridge.debt.controller;

import com.tbridge.debt.exception.ApiError;
import com.tbridge.debt.security.JwtPrincipal;
import com.tbridge.debt.config.OpenApiConfig;
import com.tbridge.debt.dto.response.CarteraResponse;
import com.tbridge.debt.dto.response.OpcionesCargaResponse;
import com.tbridge.debt.dto.response.RevisionResponse;
import com.tbridge.debt.exception.ErrorContrato;
import com.tbridge.debt.service.CargaService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

/**
 * La carga de cartera por archivo desde el portal de empresas (arrastrar y
 * soltar). Quien carga es la organizacion de la sesion, nunca un RUT que
 * venga en el formulario.
 */
@RestController
@RequestMapping("/api/debts/cartera")
@Tag(name = "Carga de cartera", description = "La planilla de la cartera, en Excel o en CSV, desde el portal de empresas")
@SecurityRequirement(name = OpenApiConfig.JWT)
public class CargaController {

    private final CargaService cargas;

    public CargaController(CargaService cargas) {
        this.cargas = cargas;
    }

    @GetMapping("/opciones")
    @Operation(summary = "Por cuenta de quien puedo cargar",
            description = "La propia empresa si es acreedora, y cada acreedor que le dio un mandato vigente, con sus campanas.")
    @ApiResponse(responseCode = "200", description = "Los acreedores y campanas")
    @ApiResponse(responseCode = "403", description = "Quien pregunta no es una empresa",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public OpcionesCargaResponse opciones(@Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal user) {
        return cargas.opciones(user);
    }

    static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    @GetMapping("/plantilla")
    @Operation(summary = "Descargar la plantilla de la cartera en Excel",
            description = """
                    Tres hojas: Instrucciones, Deudores (una fila por persona) y Deudas (una fila por cada mes que                     se debe, con el RUT de su deudor). Las listas son desplegables, las fechas y los montos tienen                     su tipo, y cada encabezado explica su columna. Va vacia: los ejemplos estan en Instrucciones.""")
    @ApiResponse(responseCode = "200", description = "La plantilla (.xlsx)",
            content = @Content(mediaType = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
    @ApiResponse(responseCode = "403", description = "Quien la pide no es una empresa",
            content = @Content(schema = @Schema(implementation = ApiError.class)))
    public ResponseEntity<byte[]> plantilla(@Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal user) {
        return ResponseEntity.ok()
                .contentType(XLSX)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("cartera-plantilla.xlsx").build().toString())
                .body(cargas.plantilla(user));
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Cargar una cartera, en Excel o en CSV, en una campana",
            description = """
                    La planilla de Excel (la de `GET /api/debts/cartera/plantilla`: una hoja de deudores y otra \
                    de deudas, una fila por mes) o el CSV del contrato (seccion 6.6: separador `;`, una fila por \
                    cargo); se distinguen por su contenido. Toda carga entra en una \
                    campana: sin `campana_id_externo` responde `400`, y en una terminada, `409`. Entra por la \
                    ingesta del contrato, con aceptacion parcial e idempotencia: cargar el mismo lote dos veces \
                    no duplica nada. Sin id de lote ni fecha de corte, se arman solos: el corte es hoy.""")
    @ApiResponse(responseCode = "200", description = "Cuantas deudas entraron y cuales no, con su motivo")
    @ApiResponse(responseCode = "400", description = "La planilla no tiene la forma de la plantilla. En Excel, "
            + "cada problema va con su hoja, fila y columna en `error.detalles`",
            content = @Content(schema = @Schema(implementation = ErrorContrato.class)))
    @ApiResponse(responseCode = "409",
            description = "La campana esta terminada, o ese id de lote ya se uso con otro contenido",
            content = @Content(schema = @Schema(implementation = ErrorContrato.class)))
    public CarteraResponse subir(
            @Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal user,
            @Parameter(description = "La planilla: .xlsx o .csv") @RequestPart("archivo") MultipartFile archivo,
            @Parameter(example = "CARGA-20260924-101500")
            @RequestParam(value = "lote_id_externo", required = false) String loteId,
            @Parameter(example = "2026-09-24") @RequestParam(value = "fecha_corte", required = false) String fechaCorte,
            @Parameter(example = "76418902-7") @RequestParam("acreedor_rut") String acreedorRut,
            @Parameter(example = "APX-CMP-8") @RequestParam(value = "campana_id_externo", required = false) String campana
    ) throws IOException {
        return cargas.subir(user, archivo.getBytes(), loteId, fechaCorte, acreedorRut, campana);
    }

    @PostMapping(value = "/revision", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Revisar una planilla antes de cargarla",
            description = """
                    Lo mismo que la carga, sin guardar nada: la planilla pasa por la misma ingesta, en una \
                    transaccion que se deshace siempre. Responde lo que pasaria con cada deuda y cuanto \
                    entraria por moneda. No deja deudas, lote ni contactos, y no invita a nadie.""")
    @ApiResponse(responseCode = "200", description = "Lo que pasaria con cada deuda, y cuanto entraria")
    @ApiResponse(responseCode = "400",
            description = "Falta la campana, o la planilla no tiene la forma de la plantilla (en Excel, cada "
                    + "problema en `error.detalles`)",
            content = @Content(schema = @Schema(implementation = ErrorContrato.class)))
    @ApiResponse(responseCode = "409", description = "La campana esta terminada",
            content = @Content(schema = @Schema(implementation = ErrorContrato.class)))
    public RevisionResponse revisar(
            @Parameter(hidden = true) @AuthenticationPrincipal JwtPrincipal user,
            @Parameter(description = "La planilla: .xlsx o .csv") @RequestPart("archivo") MultipartFile archivo,
            @Parameter(example = "CARGA-20260924-101500")
            @RequestParam(value = "lote_id_externo", required = false) String loteId,
            @Parameter(example = "2026-09-24") @RequestParam(value = "fecha_corte", required = false) String fechaCorte,
            @Parameter(example = "76418902-7") @RequestParam("acreedor_rut") String acreedorRut,
            @Parameter(example = "APX-CMP-8") @RequestParam(value = "campana_id_externo", required = false) String campana
    ) throws IOException {
        return cargas.revisar(user, archivo.getBytes(), loteId, fechaCorte, acreedorRut, campana);
    }
}
