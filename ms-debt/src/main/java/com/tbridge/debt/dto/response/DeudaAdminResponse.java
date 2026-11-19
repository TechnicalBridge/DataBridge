package com.tbridge.debt.dto.response;

import com.tbridge.debt.model.Debt;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Una deuda para el detalle de un pago en el panel (#435): cual es y de quien,
 * sin datos del deudor. El deudor se ve solo en su ficha (#434).
 */
@Schema(description = "Una deuda, sin datos del deudor")
public record DeudaAdminResponse(
        @Schema(example = "15") Long id,
        @Schema(description = "La referencia que le puso el acreedor", example = "CTR-2024-007") String referencia,
        @Schema(example = "Arriendo local comercial") String concepto,
        @Schema(example = "Patrimonio Inmuebles") String acreedor,
        @Schema(example = "76418902-7") String acreedorRut,
        @Schema(description = "La empresa que la cobra: la agencia de su campana, o el acreedor si no tiene",
                example = "APOFYX") String cobra,
        @Schema(nullable = true, example = "Arriendos septiembre") String campana,
        @Schema(example = "repacted") Debt.Status estado
) {

    public static DeudaAdminResponse de(Debt deuda) {
        return new DeudaAdminResponse(deuda.getId(), deuda.getExternalId(), deuda.getConcept(),
                deuda.getCreditor().getTradeName(), deuda.getCreditor().getRut(),
                deuda.getCampaign() != null ? deuda.getCampaign().getAgency().getTradeName()
                        : deuda.getCreditor().getTradeName(),
                deuda.getCampaign() == null ? null : deuda.getCampaign().getName(), deuda.getStatus());
    }
}
