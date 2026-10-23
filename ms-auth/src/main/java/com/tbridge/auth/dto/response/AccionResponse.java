package com.tbridge.auth.dto.response;

import com.tbridge.auth.model.AdminLog;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.hateoas.server.core.Relation;

import java.time.Instant;

/** Una linea de la bitacora del panel: que se hizo, a quien, quien y cuando. */
@Relation(collectionRelation = "acciones", itemRelation = "accion")
@Schema(description = "Algo que un admin de DataBridge hizo con una persona desde el panel")
public record AccionResponse(
        @Schema(example = "persona_creada",
                allowableValues = {"persona_creada", "admin_creado", "persona_deshabilitada", "persona_habilitada"})
        String accion,
        @Schema(description = "A quien: su correo", example = "ana.soto@empresatest.cl", nullable = true) String sobre,
        @Schema(description = "El admin que lo hizo", example = "admin@databridge.cl") String quien,
        Instant cuando
) {

    public static AccionResponse from(AdminLog registro) {
        return new AccionResponse(registro.getAction().name(), registro.getSubject(), registro.getAdminEmail(),
                registro.getOccurredAt());
    }
}
