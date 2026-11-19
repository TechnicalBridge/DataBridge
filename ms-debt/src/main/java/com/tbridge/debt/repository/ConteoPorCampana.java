package com.tbridge.debt.repository;

import com.tbridge.debt.model.Campaign;

public record ConteoPorCampana(Long id, String nombre, String empresa, Campaign.Status estado, long deudas) {
}
