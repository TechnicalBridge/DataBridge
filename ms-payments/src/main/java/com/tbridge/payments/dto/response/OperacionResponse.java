package com.tbridge.payments.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Como esta la operacion de los pagos, para la pantalla *Operacion* del panel
 * (#139): cuantos pagos esperan, la UF de hoy y los avisos a ms-debt que no se
 * han entregado. Solo totales y estados: ningun dato de un deudor.
 */
@Schema(description = "Los pagos, la UF de hoy y los avisos a ms-debt, para el panel")
public record OperacionResponse(
        Pagos pagos,
        Uf uf,
        Avisos avisos,
        @Schema(description = "Lo ultimo que se hizo desde el panel con los pagos, lo mas reciente primero (hasta 20)")
        List<Accion> acciones
) {

    @Schema(name = "AccionSobreLosPagos", description = "Algo que un admin hizo desde el panel con los pagos")
    public record Accion(
            @Schema(allowableValues = {"aviso_reintentado", "uf_cargada"}, example = "uf_cargada") String accion,
            @Schema(description = "Sobre que", example = "2026-10-27: 40123.45") String sobre,
            @Schema(description = "El admin que lo hizo", example = "admin@databridge.cl") String quien,
            Instant cuando
    ) {
    }

    @Schema(name = "PagosDeLaOperacion", description = "Cuantos pagos hay en cada situacion que hay que mirar")
    public record Pagos(
            @Schema(description = "Abiertos: esperan que la pasarela confirme, incluidos los que la pasarela esta "
                    + "verificando", example = "3")
            long abiertos,
            @Schema(description = "De los abiertos, los que llevan mas de una hora", example = "1")
            long abiertosHaceMasDeUnaHora,
            @Schema(description = "Los que vencieron en las ultimas 24 horas sin pagarse", example = "4")
            long vencidosUltimas24Horas,
            @Schema(description = "Los duplicados que falta devolver", example = "1")
            long duplicados
    ) {
    }

    @Schema(name = "UfDeHoy", description = "La UF del dia en Chile, si ya esta cargada")
    public record Uf(
            @Schema(example = "2026-10-27") LocalDate dia,
            @Schema(description = "Si ya hay valor para hoy: sin el, los cobros en UF se detienen") boolean cargada,
            @Schema(example = "40123.45", nullable = true) BigDecimal valor,
            @Schema(description = "bcentral: del Banco Central; manual: cargada a mano", example = "bcentral",
                    nullable = true) String fuente
    ) {
    }

    @Schema(name = "AvisosAMsDebt", description = "Los avisos de pago a ms-debt que no se han entregado")
    public record Avisos(
            @Schema(example = "0") long pendientes,
            @Schema(example = "1") long fallidos,
            @Schema(description = "Hasta los 200 mas recientes") List<Aviso> lista
    ) {
    }

    @Schema(name = "AvisoDePagoSinEntregar", description = "Un aviso de pago a ms-debt sin entregar, sin datos del deudor")
    public record Aviso(
            Long id,
            @Schema(description = "El id del pago que avisa", example = "58") Long pago,
            @Schema(allowableValues = {"pending", "failed"}, example = "failed") String estado,
            @Schema(example = "6") int intentos,
            @Schema(description = "Cuando se intenta de nuevo; vacio si fallo", nullable = true) Instant proximoIntento,
            @Schema(example = "Connection refused", nullable = true) String ultimoError
    ) {
    }
}
