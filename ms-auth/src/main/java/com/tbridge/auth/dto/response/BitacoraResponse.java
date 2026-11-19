package com.tbridge.auth.dto.response;

import com.tbridge.auth.model.AdminLog;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.hateoas.server.core.Relation;

import java.time.Instant;

/** Una accion de ms-auth en la bitacora unificada del panel (#443). */
@Relation(collectionRelation = "acciones", itemRelation = "accion")
@Schema(description = "Una accion administrativa registrada por ms-auth")
public record BitacoraResponse(
        Long id,
        String servicio,
        String accion,
        @Schema(description = "La persona o registro afectado", nullable = true) String sobre,
        @Schema(description = "RUT de la empresa afectada", nullable = true) String empresa,
        String quien,
        Instant cuando
) {
    public static BitacoraResponse from(AdminLog registro) {
        return new BitacoraResponse(registro.getId(), "ms-auth", registro.getAction().name(),
                registro.getSubject(), registro.getOrgRut(), registro.getAdminEmail(), registro.getOccurredAt());
    }
}
