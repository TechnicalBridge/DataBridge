package com.tbridge.auth.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Despacho de codigos de autenticacion de un solo uso por SMS.
 */
@Service
public class SmsService {
    private static final Logger log = LoggerFactory.getLogger(SmsService.class);

    private final String transport;

    public SmsService(@Value("${app.sms.transport:mock}") String transport) {
        this.transport = transport;
    }

    public void enviarCodigo(String telefono, String codigo, String duracion) {
        log.info("Despachando codigo de acceso por SMS [transporte: {}] a {}: {}",
                transport, ofuscarTelefono(telefono), codigo);
    }

    public static String ofuscarTelefono(String tel) {
        if (tel == null || tel.isBlank()) return "****";
        String limpio = tel.trim();
        if (limpio.length() <= 4) return "****";
        return limpio.substring(0, Math.min(4, limpio.length())) + "****" + limpio.substring(Math.max(0, limpio.length() - 2));
    }
}
