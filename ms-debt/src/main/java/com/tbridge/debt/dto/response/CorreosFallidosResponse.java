package com.tbridge.debt.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

/**
 * Los deudores a los que no les sale el correo, para la pantalla Operacion del
 * panel (#437). Muestra a quien fallo, de que empresa es y por que: nunca una
 * llave ni una direccion del modo de prueba.
 */
@Schema(description = "Los deudores con un correo fallido o rechazado")
public record CorreosFallidosResponse(
        @Schema(description = "Cuantos deudores tienen un correo fallido o rechazado", example = "3") long total,
        @Schema(description = "Los deudores, el rechazo mas reciente primero") List<Deudor> deudores
) {

    @Schema(name = "DeudorConCorreoFallido", description = "Un deudor y el ultimo correo que no le salio")
    public record Deudor(
            @Schema(example = "16482337-7") String rut,
            @Schema(example = "Felipe Rojas") String nombre,
            @Schema(example = "felipe.rojas@correo.cl") String email,
            @Schema(description = "Las empresas a las que debe, sin repetir", example = "APOFYX") List<String> empresas,
            @Schema(description = "Cuando fue el ultimo rechazo") Instant cuando,
            @Schema(description = "Por que no salio, con la direccion tapada",
                    example = "el servidor rechazo la direccion (550 5.1.1 User unknown)") String motivo,
            @Schema(description = "Rechazos seguidos de la direccion", example = "3") int rechazos,
            @Schema(description = "El servidor rechazo la direccion tres veces seguidas: ya no se le escribe solo",
                    example = "true") boolean conProblemas
    ) {
    }
}
