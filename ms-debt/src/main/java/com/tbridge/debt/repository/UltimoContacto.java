package com.tbridge.debt.repository;

import java.time.Instant;

/** La ultima vez que se le escribio al deudor por una deuda. */
public record UltimoContacto(Long deuda, Instant cuando) {
}
