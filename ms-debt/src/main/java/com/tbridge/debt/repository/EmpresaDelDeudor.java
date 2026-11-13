package com.tbridge.debt.repository;

/**
 * Una empresa a la que debe un deudor, para los correos fallidos del panel
 * (#437). Es solo el nombre que se muestra: no trae la deuda.
 */
public record EmpresaDelDeudor(Long deudor, String empresa) {
}
