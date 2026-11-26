package com.tbridge.debt.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDate;

/**
 * Una campana para el panel: quien la gestiona, para quien cobra, sus fechas,
 * cuantas deudas tiene y cuantos contactos salieron (#467, #441). Sin datos de
 * los deudores.
 */
@Schema(description = "Una campana de cualquier empresa, con sus deudas y sus contactos, sin datos de deudores")
public record CampanaAdminResponse(
        Long id,
        String nombre,
        @Schema(description = "La empresa que la gestiona") String empresa,
        @Schema(description = "El acreedor para quien cobra") String acreedor,
        @Schema(allowableValues = {"running", "paused", "finished"}) String estado,
        LocalDate desde,
        @Schema(nullable = true) LocalDate hasta,
        long deudas,
        @Schema(description = "Las que siguen abiertas: a esas les escribe la campana") long deudasAbiertas,
        @Schema(description = "Correos a los deudores de la campana hoy, en Chile: invitacion, toques, "
                + "recordatorios y reenvios") long contactosHoy,
        @Schema(description = "Los mismos en los ultimos siete dias, hoy incluido: la semana del limite de la ley")
        long contactosSemana
) {
}
