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
 * confianza —el nginx del portal—, que ademas la SOBRESCRIBE con la IP que vio.
 * Si un proxy confiable intermedio concatena saltos en lugar de sobrescribir, el
 * gateway recorre {@code X-Forwarded-For} de derecha a izquierda descartando los
 * proxies de confianza (e ignora {@code X-Real-IP}, que un proxy concatenador
 * suele dejar pasar tal cual); si todos los saltos son de confianza, toma el
 * primero de la izquierda. Si le habla cualquier otro, su cabecera no vale
 * nada y cuenta la direccion del par.
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
        String[] partes = reenviadoPor.split(",");
        for (int i = partes.length - 1; i >= 0; i--) {
            String ip = partes[i].trim();
            if (!ip.isEmpty() && !confiables.matcher(ip).matches()) {
                return ip;
            }
        }
        for (int i = 0; i < partes.length; i++) {
            String ip = partes[i].trim();
            if (!ip.isEmpty()) {
                return ip;
            }
        }
        return par;
    }
}
