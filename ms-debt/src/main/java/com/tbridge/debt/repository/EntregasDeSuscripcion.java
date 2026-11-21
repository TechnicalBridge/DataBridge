package com.tbridge.debt.repository;

import java.time.Instant;

/** Como van las entregas de una suscripcion: la ultima que llego, y cuantas esperan o fallaron (#438). */
public record EntregasDeSuscripcion(Long suscripcion, Instant ultimaEntrega, Long pendientes, Long fallidas) {
}
