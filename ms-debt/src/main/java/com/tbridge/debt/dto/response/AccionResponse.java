package com.tbridge.debt.dto.response;

import com.tbridge.debt.model.AdminLog;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.hateoas.server.core.Relation;

import java.time.Instant;

/** Una linea de la bitacora del panel: que se hizo, quien y cuando. */
@Relation(collectionRelation = "acciones", itemRelation = "accion")
@Schema(description = "Algo que un admin de DataBridge hizo desde el panel")
public record AccionResponse(
        @Schema(example = "empresa_creada") String accion,
        @Schema(description = "A que o a quien", example = "Empresa Test", nullable = true) String sobre,
        @Schema(description = "El admin que lo hizo", example = "admin@databridge.cl") String quien,
        Instant cuando
) {

    public static AccionResponse from(AdminLog registro) {
        return new AccionResponse(registro.getAction().name(), registro.getSubject(), registro.getAdminEmail(),
                registro.getOccurredAt());
    }
}
