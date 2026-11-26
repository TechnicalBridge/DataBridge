package com.tbridge.debt.dto.response;

import com.tbridge.debt.model.AdminLog;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.hateoas.server.core.Relation;

import java.time.Instant;

/** Una accion de ms-debt en la bitacora unificada del panel (#443). */
@Relation(collectionRelation = "acciones", itemRelation = "accion")
@Schema(description = "Una accion administrativa registrada por ms-debt")
public record BitacoraResponse(
        Long id,
        String servicio,
        String accion,
        @Schema(nullable = true) String sobre,
        @Schema(nullable = true) String empresa,
        String quien,
        Instant cuando
) {
    public static BitacoraResponse from(AdminLog registro) {
        return new BitacoraResponse(registro.getId(), "ms-debt", registro.getAction().name(),
                registro.getSubject(), registro.getOrgRut(), registro.getAdminEmail(), registro.getOccurredAt());
    }
}
