package com.tbridge.debt.client;

import com.tbridge.debt.exception.ApiException;
import org.springframework.http.HttpStatus;

/** Fallo al establecer la conexion: esta solicitud no llego a ms-auth. No contiene datos del transporte. */
public class AuthNoContactado extends ApiException {
    public AuthNoContactado() {
        super(HttpStatus.SERVICE_UNAVAILABLE, "No se pudo conectar con el servicio de acceso");
    }
}
