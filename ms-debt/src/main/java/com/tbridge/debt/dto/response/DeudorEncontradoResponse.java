package com.tbridge.debt.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import org.springframework.hateoas.server.core.Relation;

import java.util.List;

/** Un deudor que calza con la busqueda del panel (#434): lo justo para elegir cual abrir. */
@Relation(collectionRelation = "deudores", itemRelation = "deudor")
@Schema(description = "Un deudor que calza con la busqueda del panel")
public record DeudorEncontradoResponse(
        @Schema(example = "16482337-7") String rut,
        @Schema(example = "Camila Fuentes Rojas") String nombre,
        @Schema(description = "El correo que registro el acreedor", nullable = true) String correo,
        @Schema(description = "Cuantas deudas tiene, en todas las empresas", example = "2") long deudas,
        @Schema(description = "Los acreedores de esas deudas, por nombre") List<String> empresas
) {
}
