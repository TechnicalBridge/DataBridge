package com.tbridge.gateway.troughput;

import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;

import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Utilidades compartidas para las simulaciones Gatling de gateway.
 * Centraliza URL, JWT, propiedades y el guard JUnit-vs-Gatling.
 * El token nunca se hardcodea: viene de -Dtoken / TOKEN / "GATEWAY_TOKEN", .
 */
final class PerfConfig{
    private PerfConfig(){}

    static String configuracion(String propiedad, String variable, String porOmision) {
        String valor = System.getProperty(propiedad);
        if (valor == null || valor.isBlank()) {
            valor = System.getenv(variable);
        }
        return valor == null || valor.isBlank() ? porOmision : valor.trim();
    }

    static double numero(String propiedad, String variable, double porOmision) {
        String valor = configuracion(propiedad, variable, null);
        if (valor == null) {
            return porOmision;
        }
        try {
            return Double.parseDouble(valor);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "El valor de " + propiedad + " (" + variable + ") no es un numero: " + valor, e);
        }
    }

    static String token(String... variables) {
        String valor = System.getProperty("token");
        if (valor == null || valor.isBlank()) {
            valor = System.getenv("TOKEN");
        }
        for (String variable : variables) {
            if (valor == null || valor.isBlank()) {
                valor = System.getenv(variable);
            }
        }
        return valor == null ? "" : valor.trim();
    }

    static HttpProtocolBuilder protocolo(String baseUrl, String ua) {
        return http.baseUrl(baseUrl)
                .acceptHeader("application/json")
                .contentTypeHeader("application/json")
                .userAgentHeader(ua);
    }

    static boolean ejecutandoConGatling() {
        for (StackTraceElement e : Thread.currentThread().getStackTrace()) {
            if (e.getClassName().startsWith("io.gatling.")) {
                return true;
            }
        }
        return false;
    }

    static int segundos(String propiedad, String variable, int porOmision) {
        return (int) numero(propiedad, variable, porOmision);
    }
}
