package com.tbridge.debt.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.hateoas.server.core.Relation;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Una empresa, como la ve el admin de DataBridge: quien es, con quien tiene
 * mandato y cuanto hay en su cartera.
 *
 * <p>Los totales son eso, totales. Aqui no viaja ninguna deuda ni ningun dato
 * de un deudor: la cartera de una empresa la ve solo ella.
 */
@Relation(collectionRelation = "empresas", itemRelation = "empresa")
@Schema(description = "Una empresa registrada en DataBridge, con los totales de su cartera y ningun dato de un deudor")
public record EmpresaResponse(
        @Schema(example = "77305118-6") String rut,
        @Schema(example = "APOFYX SpA") String razonSocial,
        @Schema(example = "APOFYX") String nombre,
        @Schema(description = "acreedor, agencia o ambos", example = "agencia") String tipo,
        @Schema(description = "Si puede operar", example = "true") boolean activa,
        Instant creadaEl,
        @Schema(description = "Las deudas de su cartera: las suyas y las que entrego como agencia", example = "12")
        long deudas,
        @Schema(description = "Las que siguen en gestion: ni pagadas ni retiradas", example = "9") long enGestion,
        @Schema(description = "Lo que falta por cobrar, por moneda") List<Monto> porCobrar,
        @Schema(description = "Con quien tiene mandato, como agencia o como acreedor") List<Mandato> mandatos
) {

    @Schema(description = "Un total en una moneda")
    public record Monto(
            @Schema(example = "CLP") String moneda,
            @Schema(example = "4250000") BigDecimal monto
    ) {
    }

    @Schema(description = "Un mandato de la empresa, visto desde ella")
    public record Mandato(
            @Schema(description = "cobra_para: ella es la agencia. le_cobra: ella es el acreedor y la otra, su agencia",
                    example = "cobra_para") String papel,
            @Schema(description = "El RUT de la otra empresa", example = "76418902-7") String rut,
            @Schema(description = "El nombre de la otra empresa", example = "Patrimonio Inmuebles") String nombre,
            LocalDate desde,
            @Schema(nullable = true) LocalDate hasta,
            @Schema(example = "true") boolean vigente,
            @Schema(allowableValues = {"vigente", "por_empezar", "vencido", "terminado"}, example = "vigente")
            String estado
    ) {
    }
}
