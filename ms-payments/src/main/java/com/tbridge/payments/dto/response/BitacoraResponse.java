package com.tbridge.payments.dto.response;

import com.tbridge.payments.model.AdminLog;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.hateoas.server.core.Relation;

import java.time.Instant;

/** Una accion de ms-payments en la bitacora unificada del panel (#443). */
@Relation(collectionRelation = "acciones", itemRelation = "accion")
@Schema(description = "Una accion administrativa registrada por ms-payments")
public record BitacoraResponse(
        Long id,
        String servicio,
        String accion,
        @Schema(nullable = true) String sobre,
        @Schema(description = "Los registros actuales de pagos no guardan empresa", nullable = true) String empresa,
        String quien,
        Instant cuando
) {
    public static BitacoraResponse from(AdminLog registro) {
        return new BitacoraResponse(registro.getId(), "ms-payments", registro.getAction().name(),
                registro.getSubject(), null, registro.getAdminEmail(), registro.getOccurredAt());
    }
}
