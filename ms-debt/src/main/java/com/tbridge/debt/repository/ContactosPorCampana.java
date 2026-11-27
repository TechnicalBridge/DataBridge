package com.tbridge.debt.repository;

/** Cuantos contactos tuvieron las deudas de una campana: hoy y en los ultimos siete dias (#441). */
public record ContactosPorCampana(Long campana, long hoy, long semana) {
}
