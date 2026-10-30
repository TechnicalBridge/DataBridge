package com.tbridge.debt.dto.response;

import com.tbridge.debt.model.Mandate;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** Un mandato de la agencia, como lo ve en su portal (#234). */
@Schema(description = "Un mandato de la agencia: por cuenta de quién cobra, desde y hasta cuándo")
public record MandatoPortalResponse(
        @Schema(example = "4") Long id,
        @Schema(example = "76418902-7") String acreedorRut,
        @Schema(example = "Patrimonio Inmuebles") String acreedor,
        LocalDate desde,
        @Schema(description = "Vacío: sin fecha de término", nullable = true) LocalDate hasta,
        @Schema(example = "120") int moraMaximaDias,
        @Schema(description = "El % de los intereses de mora que el acreedor autoriza condonar", nullable = true,
                example = "50") BigDecimal descuentoMaximoMora,
        @Schema(allowableValues = {"vigente", "por_empezar", "vencido", "terminado"}, example = "vigente",
                description = "Con uno vigente se carga cartera y se crean campañas") String estado,
        @Schema(description = "Quién lo declaró: la persona de la agencia, o la agencia si llegó por la API",
                example = "camila.reyes@apofyx.cl", nullable = true) String declaradoPor,
        Instant declaradoEl,
        @Schema(nullable = true) String renovadoPor,
        @Schema(nullable = true) Instant renovadoEl,
        @Schema(nullable = true) String terminadoPor,
        @Schema(nullable = true) Instant terminadoEl,
        @Schema(description = "Las deudas que cargó con él y siguen en gestión: se cobran aunque termine",
                example = "12") long deudasEnGestion
) {

    public static MandatoPortalResponse de(Mandate mandato, LocalDate hoy, long deudasEnGestion) {
        return new MandatoPortalResponse(mandato.getId(), mandato.getCreditor().getRut(),
                mandato.getCreditor().getTradeName(), mandato.getValidFrom(), mandato.getValidTo(),
                mandato.getMaxOverdueDays(), mandato.getMaxMoraDiscount(), mandato.estadoAl(hoy),
                mandato.getDeclaredBy(), mandato.getCreatedAt(), mandato.getRenewedBy(), mandato.getRenewedAt(),
                mandato.getEndedBy(), mandato.getEndedAt(), deudasEnGestion);
    }
}
