package com.tbridge.debt.dto.response;

import java.time.Instant;
import java.util.List;

/** Reclamos de todas las empresas: solo el detalle necesario para seguimiento. */
public record ReclamosAdminResponse(List<Reclamo> abiertos, List<Reclamo> resueltos) {
    public record Reclamo(Long deuda, String referencia, String empresa, String motivo, String detalle,
                          Instant reclamado, long diasSinRespuesta, Instant resuelto, String resultado, String nota) {
    }
}
