package com.tbridge.debt.repository;

/** Cuantas deudas tiene un deudor con un acreedor: la lista de la busqueda del panel (#434). */
public record DeudasDelDeudor(Long deudor, String acreedor, long deudas) {
}
