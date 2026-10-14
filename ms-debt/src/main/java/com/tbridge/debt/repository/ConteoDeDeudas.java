package com.tbridge.debt.repository;

import com.tbridge.debt.model.Debt;

/**
 * Cuantas deudas hay de un acreedor, entregadas por un emisor, en un estado.
 * Es un total: no trae ninguna deuda ni a ningun deudor.
 */
public record ConteoDeDeudas(Long acreedor, Long emisor, Debt.Status estado, long deudas) {
}
