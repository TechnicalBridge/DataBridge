package com.tbridge.debt.config;

import com.tbridge.debt.model.SecretoCifrado;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** El secreto de las suscripciones, cifrado en la base y en claro para quien firma. */
class CifradoTest {

    private final Cifrado cifrado = new Cifrado("llave-de-prueba");

    @Test
    void cifraYDescifraDeVueltaSinDejarElValorALaVista() {
        String guardado = cifrado.cifrar("whsec_de_prueba");
        assertThat(guardado).startsWith("enc:v1:").doesNotContain("whsec_de_prueba");
        assertThat(cifrado.descifrar(guardado)).isEqualTo("whsec_de_prueba");
        assertThat(cifrado.cifrar("whsec_de_prueba")).as("cada vez con su propio IV").isNotEqualTo(guardado);
    }

    @Test
    void loVacioLoYaCifradoYLoDeAntesPasanIgual() {
        assertThat(cifrado.cifrar(null)).isNull();
        assertThat(cifrado.cifrar("")).isEmpty();
        String una = cifrado.cifrar("x");
        assertThat(cifrado.cifrar(una)).as("no se cifra dos veces").isEqualTo(una);
        assertThat(cifrado.descifrar("en-claro-de-antes")).isEqualTo("en-claro-de-antes");
    }

    @Test
    void conOtraLlaveOConUnByteCambiadoNoSeDescifra() {
        String guardado = cifrado.cifrar("whsec_de_prueba");
        assertThatThrownBy(() -> new Cifrado("otra-llave").descifrar(guardado))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CIFRADO_LLAVE");

        byte[] bytes = Base64.getDecoder().decode(guardado.substring(Cifrado.PREFIJO.length()));
        bytes[bytes.length - 1] ^= 1;
        String alterado = Cifrado.PREFIJO + Base64.getEncoder().encodeToString(bytes);
        assertThatThrownBy(() -> cifrado.descifrar(alterado)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void elConvertidorGuardaCifradoYEntregaEnClaro() {
        SecretoCifrado convertidor = new SecretoCifrado(cifrado);
        String columna = convertidor.convertToDatabaseColumn("whsec_de_prueba");
        assertThat(columna).startsWith("enc:v1:");
        assertThat(convertidor.convertToEntityAttribute(columna)).isEqualTo("whsec_de_prueba");
    }

    @Test
    void alArrancarSeCifranLosQueQuedaronEnClaro() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq("enc:v1:%")))
                .thenReturn(List.of(Map.of("id", 7L, "secret", "whsec_en_claro")));

        new CifrarSecretosGuardados(jdbc, cifrado).run(null);

        verify(jdbc).update(eq("UPDATE subscriptions SET secret = ? WHERE id = ?"),
                argThat((String s) -> "whsec_en_claro".equals(cifrado.descifrar(s)) && s.startsWith("enc:v1:")),
                eq(7L));
    }

    @Test
    void siNoQuedaNadaEnClaroNoSeEscribe() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq("enc:v1:%"))).thenReturn(List.of());

        new CifrarSecretosGuardados(jdbc, cifrado).run(null);

        verify(jdbc, never()).update(anyString(), (Object[]) org.mockito.ArgumentMatchers.any());
    }

    // ---- Cambiar CIFRADO_LLAVE sin perder los secretos (#258) -----------------

    private final Cifrado vieja = new Cifrado("llave-vieja");
    private final Cifrado cambiando = new Cifrado("llave-nueva", "llave-vieja");

    @Test
    void conLaAnteriorSeSigueLeyendoYSeCifraSoloConLaNueva() {
        String deAntes = vieja.cifrar("whsec_de_prueba");

        assertThat(cambiando.tieneAnterior()).isTrue();
        assertThat(cambiando.descifrar(deAntes)).isEqualTo("whsec_de_prueba");
        assertThat(new Cifrado("llave-nueva").descifrar(cambiando.cifrar("whsec_de_prueba")))
                .as("lo nuevo se cifra con la nueva, y se lee sin la anterior")
                .isEqualTo("whsec_de_prueba");
    }

    @Test
    void unSecretoDeLaAnteriorPasaALaNuevaYUnoDeLaNuevaSeQuedaComoEsta() {
        String recifrado = cambiando.conLaLlaveActual(vieja.cifrar("whsec_de_prueba"));

        assertThat(recifrado).startsWith("enc:v1:");
        assertThat(new Cifrado("llave-nueva").descifrar(recifrado)).isEqualTo("whsec_de_prueba");
        assertThat(cambiando.conLaLlaveActual(recifrado)).as("ya esta con la nueva").isNull();
        assertThat(cambiando.conLaLlaveActual("en-claro-de-antes")).as("eso lo cifra CifrarSecretosGuardados").isNull();
    }

    @Test
    void unSecretoQueNoAbreConNingunaDeLasDosFallaEnVezDePerderse() {
        String deOtra = new Cifrado("otra-llave").cifrar("whsec_de_prueba");

        assertThatThrownBy(() -> cambiando.conLaLlaveActual(deOtra))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("CIFRADO_LLAVE_ANTERIOR");
        assertThatThrownBy(() -> cambiando.descifrar(deOtra)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new Cifrado("llave-nueva").conLaLlaveActual(vieja.cifrar("x")))
                .as("sin anterior, no hay con que abrirlo")
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unaAnteriorVaciaOIgualALaActualNoCuenta() {
        assertThat(new Cifrado("llave-nueva", "").tieneAnterior()).isFalse();
        assertThat(new Cifrado("llave-nueva", "  ").tieneAnterior()).isFalse();
        assertThat(new Cifrado("llave-nueva", "llave-nueva").tieneAnterior()).isFalse();
    }

    @Test
    void sinAnteriorAlArrancarNoSeTocaLaBase() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);

        new RecifrarSecretosGuardados(jdbc, new Cifrado("llave-nueva")).run(null);

        verifyNoInteractions(jdbc);
    }

    @Test
    void conAnteriorAlArrancarPasaALaNuevaSoloLosQueEstanConLaVieja() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        String deAntes = vieja.cifrar("whsec_siete");
        String yaNueva = new Cifrado("llave-nueva").cifrar("whsec_ocho");
        when(jdbc.queryForList(anyString(), eq("enc:v1:%")))
                .thenReturn(List.of(Map.of("id", 7L, "secret", deAntes), Map.of("id", 8L, "secret", yaNueva)));
        when(jdbc.update(anyString(), anyString(), eq(7L), eq(deAntes))).thenReturn(1);

        new RecifrarSecretosGuardados(jdbc, cambiando).run(null);

        verify(jdbc).queryForList(argThat((String sql) -> sql.endsWith("FOR UPDATE")), eq("enc:v1:%"));
        verify(jdbc).update(eq("UPDATE subscriptions SET secret = ? WHERE id = ? AND secret = ?"),
                argThat((String s) -> "whsec_siete".equals(new Cifrado("llave-nueva").descifrar(s))),
                eq(7L), eq(deAntes));
        verify(jdbc, never()).update(anyString(), anyString(), eq(8L), anyString());
    }

    @Test
    void siUnoNoAbreConNingunaNoArrancaYLaTransaccionSeDeshace() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        String deAntes = vieja.cifrar("whsec_siete");
        when(jdbc.queryForList(anyString(), eq("enc:v1:%"))).thenReturn(List.of(
                Map.of("id", 7L, "secret", deAntes),
                Map.of("id", 9L, "secret", new Cifrado("otra-llave").cifrar("whsec_nueve"))));
        when(jdbc.update(anyString(), anyString(), eq(7L), eq(deAntes))).thenReturn(1);

        assertThatThrownBy(() -> new RecifrarSecretosGuardados(jdbc, cambiando).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("suscripcion 9")
                .hasMessageContaining("no se cambio ningun secreto")
                .hasMessageNotContaining("whsec");
    }

    @Test
    void siUnaFilaCambioPorDebajoNoArranca() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForList(anyString(), eq("enc:v1:%")))
                .thenReturn(List.of(Map.of("id", 7L, "secret", vieja.cifrar("whsec_siete"))));
        when(jdbc.update(anyString(), anyString(), eq(7L), anyString())).thenReturn(0);

        assertThatThrownBy(() -> new RecifrarSecretosGuardados(jdbc, cambiando).run(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cambio mientras se volvia a cifrar");
    }

    @Test
    void laRecifradaVaEnUnaTransaccion() throws NoSuchMethodException {
        assertThat(RecifrarSecretosGuardados.class.getMethod("run", org.springframework.boot.ApplicationArguments.class)
                .isAnnotationPresent(org.springframework.transaction.annotation.Transactional.class)).isTrue();
    }
}
