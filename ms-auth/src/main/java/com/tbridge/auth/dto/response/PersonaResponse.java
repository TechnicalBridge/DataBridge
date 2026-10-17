package com.tbridge.auth.dto.response;

import com.tbridge.auth.model.StaffUser;
import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.hateoas.server.core.Relation;

import java.time.Instant;

/** Una persona con cuenta, como la ve el admin de DataBridge en su panel. */
@Relation(collectionRelation = "personas", itemRelation = "persona")
@Schema(description = "Una persona con cuenta: el usuario de una empresa, o un admin de DataBridge")
public record PersonaResponse(
        @Schema(example = "7") Long id,
        @Schema(example = "ana.soto@empresatest.cl") String correo,
        @Schema(example = "Ana Soto") String nombre,
        @Schema(description = "usuario, de una empresa; admin, de DataBridge", example = "usuario",
                allowableValues = {"usuario", "admin"})
        String rol,
        @Schema(description = "El RUT de su empresa. Un admin no tiene", example = "76086428-5", nullable = true)
        String empresaRut,
        @Schema(description = "Si puede entrar", example = "true") boolean habilitada,
        Instant creadaEl,
        @Schema(description = "La ultima vez que entro. Vacio si todavia no entra", nullable = true)
        Instant ultimoAcceso,
        @Schema(description = "Desde cuando no puede entrar", nullable = true) Instant deshabilitadaEl
) {

    public static PersonaResponse from(StaffUser persona) {
        return new PersonaResponse(persona.getId(), persona.getEmail(), persona.getFullName(),
                persona.getRole().name(), persona.getOrgRut(), persona.habilitado(), persona.getCreatedAt(),
                persona.getLastLoginAt(), persona.getDisabledAt());
    }
}
