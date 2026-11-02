package com.tbridge.debt.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * Los avisos a las empresas que todavia no se entregan, para la pantalla
 * *Operacion* del panel (#139), agrupados por destinatario. Sin el contenido de
 * ningun aviso: lleva datos de la deuda.
 */
@Schema(description = "Los avisos a las empresas que no se han entregado, por destinatario")
public record AvisosResponse(
        @Schema(description = "Cuantos esperan su proximo intento", example = "2") long pendientes,
        @Schema(description = "Cuantos se rindieron despues del sexto intento y esperan un reintento a mano",
                example = "21") long fallidos,
        @Schema(description = "Por destinatario, el que tiene mas fallidos primero. Trae hasta los 500 mas recientes")
        List<Destino> destinos
) {

    @Schema(name = "DestinoDeAvisos", description = "Una suscripcion de una empresa y sus avisos sin entregar")
    public record Destino(
            @Schema(description = "El id de la suscripcion", example = "1") Long suscripcion,
            @Schema(example = "APOFYX") String empresa,
            @Schema(example = "77305118-6") String rut,
            @Schema(description = "A donde se entregan: esquema, host y puerto, sin la ruta ni los parametros",
                    example = "https://apofyx.cl") String direccion,
            @Schema(example = "0") long pendientes,
            @Schema(example = "21") long fallidos,
            List<Aviso> avisos
    ) {
    }

    @Schema(name = "AvisoSinEntregar", description = "Un aviso que no se ha entregado: que fue, sus intentos y su "
            + "ultimo error. Sin su contenido")
    public record Aviso(
            Long id,
            @Schema(example = "pago.confirmado") String tipo,
            @Schema(description = "Cuando paso lo que avisa") Instant ocurrio,
            @Schema(allowableValues = {"pending", "failed"}, example = "failed") String estado,
            @Schema(example = "6") int intentos,
            @Schema(description = "Cuando se intenta de nuevo; vacio si fallo y espera un reintento a mano",
                    nullable = true) Instant proximoIntento,
            @Schema(description = "Lo que respondio el destinatario la ultima vez, o por que no se pudo",
                    example = "Connection refused", nullable = true) String ultimoError
    ) {
    }
}
