package com.tbridge.debt.exception;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

/**
 * La forma de un error del contrato de integracion:
 * {@code {"error": {"codigo": "...", "mensaje": "..."}}}.
 *
 * <p>Distinta de la del portal a proposito: quien la lee es un sistema, y
 * decide por el codigo, no por el texto.
 */
@Schema(description = "Un error que invalida el envio completo")
public record ErrorContrato(Detalle error) {

    @Schema(description = "El motivo, con un codigo estable para que el emisor decida")
    public record Detalle(
            @Schema(example = "lote_id_reutilizado") String codigo,
            @Schema(example = "El lote APX-2026-09-19-004 ya se recibio con otro contenido") String mensaje,
            @Schema(description = "Cada problema de una planilla, con su hoja, su fila y su columna. Solo cuando hay varios",
                    example = "[\"Hoja Deudas, fila 7, columna Monto: '185.000,5' no es un numero\"]")
            @JsonInclude(JsonInclude.Include.NON_EMPTY) List<String> detalles
    ) {
    }

    public static ErrorContrato de(CarteraInvalida fallo) {
        return new ErrorContrato(new Detalle(fallo.getCodigo(), fallo.getMessage(), fallo.getDetalles()));
    }
}
