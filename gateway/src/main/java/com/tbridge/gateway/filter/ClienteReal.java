package com.tbridge.gateway.filter;

import org.springframework.http.server.reactive.ServerHttpRequest;

import java.util.regex.Pattern;

/**
 * Quien es de verdad el cliente de una peticion.
 *
 * <p>La cabecera {@code X-Forwarded-For} dice de que IP venia la peticion antes
 * de pasar por los proxies, pero la puede escribir cualquiera. Si se le cree
 * siempre, un atacante que mande un valor distinto en cada intento obtiene un
 * cupo nuevo del limitador cada vez: el limite de peticiones deja de existir.
 *
 * <p>La regla: solo se le cree cuando quien le habla al gateway es un proxy de
 * confianza —el nginx del portal—, que ademas la SOBRESCRIBE con la IP que vio
 * en vez de agregarla detras de lo que mando el cliente. Si le habla cualquier
 * otro, su {@code X-Forwarded-For} no vale nada y cuenta la direccion del par.
 *
 * <p>Los proxies de confianza son los mismos que Spring Cloud Gateway usa para
 * reenviar esa cabecera a los servicios
 * ({@code spring.cloud.gateway.server.webflux.trusted-proxies}): una sola
 * definicion de "en quien se confia" para las dos cosas.
 */
public final class ClienteReal {

    private final Pattern confiables;

    public ClienteReal(String patronDeConfiables) {
        this.confiables = Pattern.compile(patronDeConfiables);
    }

    String de(ServerHttpRequest peticion) {
        String par = peticion.getRemoteAddress() != null && peticion.getRemoteAddress().getAddress() != null
                ? peticion.getRemoteAddress().getAddress().getHostAddress()
                : null;
        return resolver(par, peticion.getHeaders().getFirst("X-Forwarded-For"));
    }

    /** La regla sola, sin la peticion alrededor: es lo que prueban las pruebas. */
    public String resolver(String par, String reenviadoPor) {
        if (par == null) {
            return "desconocido";
        }
        if (!confiables.matcher(par).matches()) {
            return par;
        }
        if (reenviadoPor == null || reenviadoPor.isBlank()) {
            return par;
        }
        String primero = reenviadoPor.split(",")[0].trim();
        return primero.isEmpty() ? par : primero;
    }
}
