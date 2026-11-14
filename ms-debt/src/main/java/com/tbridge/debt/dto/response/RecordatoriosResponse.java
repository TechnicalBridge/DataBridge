package com.tbridge.debt.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/** Lo que hizo una pasada de los recordatorios. */
@Schema(description = "Cuantos recordatorios se encolaron y cuantos no")
public record RecordatoriosResponse(
        @Schema(example = "2") int enviados,
        @Schema(description = "Sin correo, con los avisos apagados, o con ms-auth sin responder", example = "1")
        int omitidos,
        @Schema(description = "enviados cuenta intenciones persistidas") String estado
) {
    public RecordatoriosResponse(int enviados, int omitidos) { this(enviados, omitidos, "QUEUED"); }
}
