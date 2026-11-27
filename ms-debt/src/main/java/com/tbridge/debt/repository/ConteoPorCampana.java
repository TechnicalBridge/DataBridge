package com.tbridge.debt.repository;

import com.tbridge.debt.model.Campaign;

import java.time.LocalDate;

/** Una campana para el panel, con cuantas deudas tiene y cuantas siguen abiertas (#467, #441). */
public record ConteoPorCampana(Long id, String nombre, String empresa, String acreedor, Campaign.Status estado,
                               LocalDate desde, LocalDate hasta, long deudas, long abiertas) {
}
