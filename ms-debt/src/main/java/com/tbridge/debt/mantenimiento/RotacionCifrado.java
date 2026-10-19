package com.tbridge.debt.mantenimiento;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.debt.config.Cifrado;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/** Mantenimiento fuera de Spring: no arranca tareas, correo ni consumidores. */
public final class RotacionCifrado {

    private RotacionCifrado() { }

    private record Cambio(long id, String anterior, String nuevo) { }

    public static int ejecutar(Connection conexion, Cifrado anterior, Cifrado nuevo,
                               boolean aplicar) throws SQLException {
        if (!conexion.getAutoCommit()) {
            throw new IllegalArgumentException("La rotacion requiere una conexion propia sin transaccion previa");
        }
        conexion.setAutoCommit(false);
        boolean confirmado = false;
        boolean commitIntentado = false;
        try {
            var cambios = new ArrayList<Cambio>();
            // Se comprueban todos antes de la primera escritura; el bloqueo dura hasta terminar.
            try (var consulta = conexion.prepareStatement("SELECT id, secret FROM subscriptions ORDER BY id FOR UPDATE");
                 var filas = consulta.executeQuery()) {
                while (filas.next()) {
                    String original = filas.getString("secret");
                    if (original == null || original.isBlank() || !Cifrado.estaCifrado(original)) {
                        throw new IllegalStateException("Hay secretos sin cifrar o vacios; revisar antes de rotar");
                    }
                    String claro = anterior.descifrar(original);
                    if (claro.isBlank() || Cifrado.estaCifrado(claro)) {
                        throw new IllegalStateException("Hay un secreto con formato no admitido para la rotacion");
                    }
                    String reemplazo = nuevo.cifrar(claro);
                    if (!claro.equals(nuevo.descifrar(reemplazo))) {
                        throw new IllegalStateException("La llave nueva no supera la comprobacion de ida y vuelta");
                    }
                    cambios.add(new Cambio(filas.getLong("id"), original, reemplazo));
                }
            }
            // La lectura mantiene el bloqueo de metadatos: el motor no puede cambiar antes del commit.
            try (var consulta = conexion.prepareStatement(
                    "SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'subscriptions'");
                 var motor = consulta.executeQuery()) {
                if (!motor.next() || !"InnoDB".equalsIgnoreCase(motor.getString("ENGINE"))) {
                    throw new IllegalStateException("La rotacion requiere subscriptions con motor InnoDB");
                }
            }
            if (aplicar) {
                try (var actualizar = conexion.prepareStatement(
                        "UPDATE subscriptions SET secret = ? WHERE id = ? AND secret = ?")) {
                    for (var cambio : cambios) {
                        actualizar.setString(1, cambio.nuevo());
                        actualizar.setLong(2, cambio.id());
                        actualizar.setString(3, cambio.anterior());
                        if (actualizar.executeUpdate() != 1) {
                            throw new SQLException("Una suscripcion cambio durante la rotacion");
                        }
                    }
                }
                Map<Long, String> esperados = new LinkedHashMap<>();
                for (var cambio : cambios) esperados.put(cambio.id(), cambio.nuevo());
                try (var consulta = conexion.prepareStatement("SELECT id, secret FROM subscriptions ORDER BY id");
                     var filas = consulta.executeQuery()) {
                    while (filas.next()) {
                        String esperado = esperados.remove(filas.getLong("id"));
                        if (esperado == null || !esperado.equals(filas.getString("secret"))) {
                            throw new SQLException("La verificacion posterior no coincide con la rotacion");
                        }
                    }
                }
                if (!esperados.isEmpty()) throw new SQLException("Faltan suscripciones despues de la rotacion");
                commitIntentado = true;
                conexion.commit();
                confirmado = true;
            } else {
                conexion.rollback();
            }
            return cambios.size();
        } catch (Exception fallo) {
            try {
                conexion.rollback();
            } catch (SQLException rollbackFallido) {
                throw new EstadoIncierto();
            }
            if (commitIntentado && !confirmado) throw new EstadoIncierto();
            if (fallo instanceof SQLException sql) throw sql;
            if (fallo instanceof RuntimeException runtime) throw runtime;
            throw new IllegalStateException("No se pudo comprobar la rotacion");
        }
    }

    public static final class EstadoIncierto extends SQLException {
        private EstadoIncierto() { super("Estado de la transaccion incierto; mantener el servicio detenido"); }
    }

    static int ejecutarYCerrar(Connection conexion, Cifrado anterior, Cifrado nuevo,
                              boolean aplicar) throws SQLException {
        boolean confirmado = false;
        try (conexion) {
            int filas = ejecutar(conexion, anterior, nuevo, aplicar);
            confirmado = aplicar;
            return filas;
        } catch (SQLException fallo) {
            if (confirmado) throw new EstadoIncierto();
            throw fallo;
        }
    }

    public static void main(String[] args) {
        int codigo = 2;
        try {
            if (args.length != 0) throw new IllegalArgumentException("Las credenciales solo se reciben por entrada estandar");
            var entrada = new ObjectMapper().readTree(System.in);
            String modo = entrada.path("modo").asText();
            if (!modo.equals("verificar") && !modo.equals("aplicar")) throw new IllegalArgumentException();
            String url = entrada.path("url").asText();
            String vieja = entrada.path("anterior").asText();
            String nueva = entrada.path("nueva").asText();
            if (!url.startsWith("jdbc:mysql://") || vieja.isBlank() || nueva.isBlank()
                    || (modo.equals("aplicar") && vieja.equals(nueva))) throw new IllegalArgumentException();
            var propiedades = new Properties();
            propiedades.setProperty("user", entrada.path("usuario").asText());
            propiedades.setProperty("password", entrada.path("password").asText());
            propiedades.setProperty("connectTimeout", "10000");
            propiedades.setProperty("socketTimeout", "60000");
            int filas = ejecutarYCerrar(DriverManager.getConnection(url, propiedades),
                    new Cifrado(vieja), new Cifrado(nueva), modo.equals("aplicar"));
            System.out.println("{\"correcto\":true,\"filas\":" + filas + "}");
            codigo = 0;
        } catch (EstadoIncierto fallo) {
            System.out.println("{\"correcto\":false,\"estadoIncierto\":true}");
            codigo = 3;
        } catch (Exception fallo) {
            // Ni el mensaje del driver, ni la causa, ni el JSON de entrada son evidencia publicable.
            System.out.println("{\"correcto\":false,\"estadoIncierto\":false}");
        }
        System.exit(codigo);
    }
}
