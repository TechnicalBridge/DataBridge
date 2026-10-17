package com.tbridge.debt.dto.response;

import com.tbridge.debt.model.Organization;
import io.swagger.v3.oas.annotations.media.Schema;

/** Que una empresa existe, y como se llama: lo que ms-auth pregunta antes de darle una persona. */
@Schema(description = "Una empresa registrada, para un servicio hermano")
public record OrganizacionResponse(
        @Schema(example = "77305118-6") String rut,
        @Schema(example = "APOFYX") String nombre,
        @Schema(example = "true") boolean activa
) {

    public static OrganizacionResponse from(Organization organizacion) {
        return new OrganizacionResponse(organizacion.getRut(), organizacion.getTradeName(),
                organizacion.getStatus() == Organization.Status.active);
    }
}
