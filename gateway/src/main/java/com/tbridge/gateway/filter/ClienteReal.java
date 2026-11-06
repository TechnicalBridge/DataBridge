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
 * <p>Y se lee de derecha a izquierda (#349): un proxy que AGREGA pone al final
 * la IP que vio, y lo de la izquierda lo pudo escribir el cliente. El cliente es
 * el primero, desde la derecha, que no es un proxy de confianza. Asi, si algun
 * dia un proxy concatena en vez de sobrescribir, inventar un valor adelante
 * sigue sin dar un cupo nuevo.
 *
 * <p>Los proxies de confianza son los mismos que Spring Cloud Gateway usa para
 * reenviar esa cabecera a los servicios
 * ({@code spring.cloud.gateway.server.webflux.trusted-proxies}): una sola
 * definicion de "en quien se confia" para las dos cosas.
 */
final class ClienteReal {

    private final Pattern confiables;

    ClienteReal(String patronDeConfiables) {
        this.confiables = Pattern.compile(patronDeConfiables);
    }

    String de(ServerHttpRequest peticion) {
        String par = peticion.getRemoteAddress() != null && peticion.getRemoteAddress().getAddress() != null
                ? peticion.getRemoteAddress().getAddress().getHostAddress()
                : null;
        return resolver(par, peticion.getHeaders().getFirst("X-Forwarded-For"));
    }

    /** La regla sola, sin la peticion alrededor: es lo que prueban las pruebas. */
    String resolver(String par, String reenviadoPor) {
        if (par == null) {
            return "desconocido";
        }
        if (!confiables.matcher(par).matches()) {
            return par;
        }
        if (reenviadoPor == null || reenviadoPor.isBlank()) {
            return par;
        }
        String[] saltos = reenviadoPor.split(",");
        String masLejano = null;
        for (int i = saltos.length - 1; i >= 0; i--) {
            String ip = saltos[i].trim();
            if (ip.isEmpty()) {
                continue;
            }
            if (!confiables.matcher(ip).matches()) {
                return ip;
            }
            masLejano = ip;
        }
        //  Todos son de confianza: el cliente esta en la red privada, y es el mas lejano.
        return masLejano == null ? par : masLejano;
    }
}
