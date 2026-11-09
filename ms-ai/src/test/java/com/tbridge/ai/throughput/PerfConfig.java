package com.tbridge.ai.throughput;

import io.gatling.javaapi.http.HttpProtocolBuilder;

import java.time.Duration;

import static io.gatling.javaapi.http.HttpDsl.*;

/**
 * Utilidades compartidas para las simulaciones Gatling de ms-ai.
 * Centraliza URL, JWT, propiedades y el guard JUnit-vs-Gatling.
 * El token nunca se hardcodea: viene de -Dtoken / TOKEN / "AI_TOKEN", .
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

    /**
     * El chat solo se mide con el motor local: con groq, gemini o xai cada
     * pregunta gasta cupo y dinero del proveedor real. Consulta la salud y
     * exige provider=local, ademas de un token.
     */
    static boolean chatConProveedorLocal(String baseUrl, String token) {
        if (token == null || token.isBlank()) {
            return false;
        }
        try {
            java.net.http.HttpClient cliente = java.net.http.HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(3))
                    .build();
            java.net.http.HttpRequest peticion = java.net.http.HttpRequest
                    .newBuilder(java.net.URI.create(baseUrl + "/api/ai/salud"))
                    .timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            java.net.http.HttpResponse<String> respuesta = cliente.send(peticion,
                    java.net.http.HttpResponse.BodyHandlers.ofString());
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"provider\"\\s*:\\s*\"([^\"]*)\"")
                    .matcher(respuesta.body());
            return m.find() && "local".equalsIgnoreCase(m.group(1));
        } catch (Exception e) {
            return false;
        }
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
