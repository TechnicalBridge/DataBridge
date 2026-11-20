package com.tbridge.debt.repository;

import com.tbridge.debt.model.Debt;

public record ConteoPorEstado(Debt.Status estado, long deudas) {
}
