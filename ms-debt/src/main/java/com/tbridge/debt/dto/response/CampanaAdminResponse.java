package com.tbridge.debt.dto.response;

/** Una campaña para el panel: únicamente identidad comercial y número de deudas. */
public record CampanaAdminResponse(Long id, String nombre, String empresa, String estado, long deudas) {
}
