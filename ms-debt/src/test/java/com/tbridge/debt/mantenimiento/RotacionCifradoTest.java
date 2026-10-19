package com.tbridge.debt.mantenimiento;

import com.tbridge.debt.config.Cifrado;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class RotacionCifradoTest {
    private final Cifrado anterior = new Cifrado("anterior-ficticia");
    private final Cifrado nueva = new Cifrado("nueva-ficticia");
    private final Connection conexion = mock(Connection.class);
    private final PreparedStatement leer = mock(PreparedStatement.class);
    private final PreparedStatement escribir = mock(PreparedStatement.class);
    private final PreparedStatement comprobar = mock(PreparedStatement.class);
    private final PreparedStatement consultarMotor = mock(PreparedStatement.class);
    private final ResultSet filas = mock(ResultSet.class);
    private final ResultSet posteriores = mock(ResultSet.class);
    private final ResultSet motor = mock(ResultSet.class);

    @BeforeEach
    void preparar() throws Exception {
        when(conexion.getAutoCommit()).thenReturn(true);
        when(conexion.prepareStatement("SELECT id, secret FROM subscriptions ORDER BY id FOR UPDATE")).thenReturn(leer);
        when(conexion.prepareStatement("UPDATE subscriptions SET secret = ? WHERE id = ? AND secret = ?")).thenReturn(escribir);
        when(conexion.prepareStatement("SELECT id, secret FROM subscriptions ORDER BY id")).thenReturn(comprobar);
        when(conexion.prepareStatement("SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'subscriptions'"))
                .thenReturn(consultarMotor);
        when(consultarMotor.executeQuery()).thenReturn(motor);
        when(motor.next()).thenReturn(true);
        when(motor.getString("ENGINE")).thenReturn("InnoDB");
        when(leer.executeQuery()).thenReturn(filas);
        when(comprobar.executeQuery()).thenReturn(posteriores);
        when(filas.next()).thenReturn(true, false);
        when(filas.getLong("id")).thenReturn(7L);
        when(filas.getString("secret")).thenReturn(anterior.cifrar("whsec_ficticio"));
        when(escribir.executeUpdate()).thenReturn(1);
    }

    @Test
    void verificarNoEscribeNiConfirma() throws Exception {
        assertThat(RotacionCifrado.ejecutar(conexion, anterior, nueva, false)).isEqualTo(1);
        verify(escribir, never()).executeUpdate();
        verify(conexion, never()).commit();
        verify(conexion).rollback();
        verify(conexion, never()).setAutoCommit(true);
    }

    @Test
    void conservaElSecretoYVerificaAntesDeConfirmar() throws Exception {
        String[] guardado = { null };
        doAnswer(a -> { guardado[0] = a.getArgument(1); return null; }).when(escribir).setString(eq(1), anyString());
        when(posteriores.next()).thenReturn(true, false);
        when(posteriores.getLong("id")).thenReturn(7L);
        when(posteriores.getString("secret")).thenAnswer(a -> guardado[0]);
        assertThat(RotacionCifrado.ejecutar(conexion, anterior, nueva, true)).isEqualTo(1);
        assertThat(nueva.descifrar(guardado[0])).isEqualTo("whsec_ficticio");
        assertThatThrownBy(() -> anterior.descifrar(guardado[0])).isInstanceOf(IllegalStateException.class);
        var orden = inOrder(escribir, comprobar, conexion);
        orden.verify(escribir).executeUpdate();
        orden.verify(comprobar).executeQuery();
        orden.verify(conexion).commit();
    }

    @Test
    void unaLlaveIncorrectaNoEscribe() {
        assertThatThrownBy(() -> RotacionCifrado.ejecutar(conexion, nueva, anterior, true)).isInstanceOf(IllegalStateException.class);
        try { verify(escribir, never()).executeUpdate(); verify(conexion).rollback(); } catch (SQLException e) { throw new AssertionError(e); }
    }

    @Test
    void unSegundoRegistroCorruptoImpideLaPrimeraEscritura() throws Exception {
        when(filas.next()).thenReturn(true, true, false);
        when(filas.getString("secret")).thenReturn(anterior.cifrar("whsec_ficticio"), "enc:v1:corrupto");
        assertThatThrownBy(() -> RotacionCifrado.ejecutar(conexion, anterior, nueva, true)).isInstanceOf(IllegalStateException.class);
        verify(escribir, never()).executeUpdate();
        verify(conexion).rollback();
    }

    @Test
    void unFalloDeEscrituraDeshaceLaTransaccion() throws Exception {
        when(escribir.executeUpdate()).thenThrow(new SQLException("fallo ficticio"));
        assertThatThrownBy(() -> RotacionCifrado.ejecutar(conexion, anterior, nueva, true)).isInstanceOf(SQLException.class);
        verify(conexion).rollback();
        verify(conexion, never()).commit();
    }

    @Test
    void unFalloEnLaSegundaEscrituraTambienRevierteLaPrimera() throws Exception {
        when(filas.next()).thenReturn(true, true, false);
        when(filas.getLong("id")).thenReturn(7L, 8L);
        when(escribir.executeUpdate()).thenReturn(1).thenThrow(new SQLException("segunda escritura ficticia fallida"));
        assertThatThrownBy(() -> RotacionCifrado.ejecutar(conexion, anterior, nueva, true)).isInstanceOf(SQLException.class);
        verify(escribir, times(2)).executeUpdate();
        verify(conexion).rollback();
        verify(conexion, never()).commit();
    }

    @Test
    void unaActualizacionSinCoincidenciaNoSeConfirma() throws Exception {
        when(escribir.executeUpdate()).thenReturn(0);
        assertThatThrownBy(() -> RotacionCifrado.ejecutar(conexion, anterior, nueva, true)).isInstanceOf(SQLException.class);
        verify(conexion, never()).commit();
    }

    @Test
    void comprobarDatosDiferentesDeshaceTodo() throws Exception {
        when(posteriores.next()).thenReturn(true, false);
        when(posteriores.getLong("id")).thenReturn(7L);
        when(posteriores.getString("secret")).thenReturn("alterado-ficticio");
        assertThatThrownBy(() -> RotacionCifrado.ejecutar(conexion, anterior, nueva, true)).isInstanceOf(SQLException.class);
        verify(conexion).rollback();
        verify(conexion, never()).commit();
    }

    @Test
    void unCommitInciertoNoSePresentaComoRollbackSeguro() throws Exception {
        when(filas.next()).thenReturn(false);
        when(posteriores.next()).thenReturn(false);
        doThrow(new SQLException("red ficticia interrumpida")).when(conexion).commit();
        assertThatThrownBy(() -> RotacionCifrado.ejecutar(conexion, anterior, nueva, true)).isInstanceOf(RotacionCifrado.EstadoIncierto.class);
        verify(conexion, never()).setAutoCommit(true);
    }

    @Test
    void unRollbackFallidoDejaEstadoIncierto() throws Exception {
        when(escribir.executeUpdate()).thenThrow(new SQLException("fallo ficticio"));
        doThrow(new SQLException("rollback ficticio fallido")).when(conexion).rollback();
        assertThatThrownBy(() -> RotacionCifrado.ejecutar(conexion, anterior, nueva, true)).isInstanceOf(RotacionCifrado.EstadoIncierto.class);
    }

    @Test
    void noParticipaEnUnaTransaccionAjena() throws Exception {
        when(conexion.getAutoCommit()).thenReturn(false);
        assertThatThrownBy(() -> RotacionCifrado.ejecutar(conexion, anterior, nueva, true)).isInstanceOf(IllegalArgumentException.class);
        verify(conexion, never()).setAutoCommit(false);
    }

    @Test
    void rechazaSecretosEnClaroAntesDeEscribir() throws Exception {
        when(filas.getString("secret")).thenReturn("whsec_ficticio");
        assertThatThrownBy(() -> RotacionCifrado.ejecutar(conexion, anterior, nueva, true)).isInstanceOf(IllegalStateException.class);
        verify(escribir, never()).executeUpdate();
    }

    @Test
    void laBaseSinSuscripcionesTambienSePuedeRotar() throws Exception {
        when(filas.next()).thenReturn(false);
        when(posteriores.next()).thenReturn(false);
        assertThat(RotacionCifrado.ejecutar(conexion, anterior, nueva, true)).isZero();
        verify(conexion).commit();
    }

    @Test
    void rechazaUnMotorSinRollbackAntesDeEscribir() throws Exception {
        when(motor.getString("ENGINE")).thenReturn("MyISAM");
        assertThatThrownBy(() -> RotacionCifrado.ejecutar(conexion, anterior, nueva, true))
                .isInstanceOf(IllegalStateException.class);
        verify(escribir, never()).executeUpdate();
        verify(conexion, never()).commit();
        verify(conexion).rollback();
    }

    @Test
    void noSuponeUnMotorTransaccionalSiFaltaSuMetadata() throws Exception {
        when(motor.next()).thenReturn(false);
        assertThatThrownBy(() -> RotacionCifrado.ejecutar(conexion, anterior, nueva, false))
                .isInstanceOf(IllegalStateException.class);
        verify(escribir, never()).executeUpdate();
        verify(conexion, never()).commit();
        verify(conexion).rollback();
    }

    @Test
    void unCierreFallidoTrasCommitNoSePresentaComoFalloRevertido() throws Exception {
        when(filas.next()).thenReturn(false);
        when(posteriores.next()).thenReturn(false);
        doThrow(new SQLException("cierre ficticio fallido")).when(conexion).close();
        assertThatThrownBy(() -> RotacionCifrado.ejecutarYCerrar(conexion, anterior, nueva, true))
                .isInstanceOf(RotacionCifrado.EstadoIncierto.class);
        verify(conexion).commit();
        verify(conexion, never()).rollback();
    }

    @Test
    void unCierreFallidoEnVerificacionNoConfirmaCambios() throws Exception {
        doThrow(new SQLException("cierre ficticio fallido")).when(conexion).close();
        assertThatThrownBy(() -> RotacionCifrado.ejecutarYCerrar(conexion, anterior, nueva, false))
                .isExactlyInstanceOf(SQLException.class);
        verify(conexion).rollback();
        verify(conexion, never()).commit();
    }
}
