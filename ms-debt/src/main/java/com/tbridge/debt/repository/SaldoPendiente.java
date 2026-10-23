package com.tbridge.debt.repository;

import com.tbridge.debt.model.Debt;

import java.math.BigDecimal;

/**
 * Lo que falta por cobrar de un acreedor, entregado por un emisor, en una
 * moneda. Es un total: no trae ninguna deuda ni a ningun deudor.
 */
public record SaldoPendiente(Long acreedor, Long emisor, Debt.Currency moneda, BigDecimal monto) {
}
