package com.tbridge.debt.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.support.ResourcePropertySource;
import org.springframework.core.io.support.SpringFactoriesLoader;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** La configuracion real, con variables de prueba y sin leer el .env ni depender de la maquina. */
class ConfiguracionObligatoriaTest {

    private static Map<String, Object> variables() {
        return new HashMap<>(Map.of(
                "MYSQL_PASSWORD", "mysql-de-prueba",
                "JWT_SECRET", "jwt-de-prueba-".repeat(4),
                "INTERNAL_KEY", "interna-de-prueba",
                "CIFRADO_LLAVE", "secreto-de-prueba"));
    }

    private static StandardEnvironment entorno(Map<String, Object> variables) throws IOException {
        StandardEnvironment entorno = new StandardEnvironment();
        entorno.getPropertySources().remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        entorno.getPropertySources().remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
        entorno.getPropertySources().addLast(new ResourcePropertySource("classpath:application.properties"));
        entorno.getPropertySources().addFirst(new MapPropertySource("prueba", variables));
        return entorno;
    }

    @Test
    void sin_db_password_usa_mysql_password() throws IOException {
        StandardEnvironment entorno = entorno(variables());
        assertThatCode(() -> new ConfiguracionObligatoria().postProcessEnvironment(entorno, null))
                .doesNotThrowAnyException();
        assertThat(entorno.getRequiredProperty("spring.datasource.password")).isEqualTo("mysql-de-prueba");
    }

    @Test
    void db_password_tiene_prioridad_sobre_mysql_password() throws IOException {
        Map<String, Object> variables = variables();
        variables.put("DB_PASSWORD", "otra-de-prueba");
        StandardEnvironment entorno = entorno(variables);
        new ConfiguracionObligatoria().postProcessEnvironment(entorno, null);
        assertThat(entorno.getRequiredProperty("spring.datasource.password")).isEqualTo("otra-de-prueba");
    }

    @Test
    void db_password_no_necesita_que_exista_mysql_password() throws IOException {
        Map<String, Object> variables = variables();
        variables.remove("MYSQL_PASSWORD");
        variables.put("DB_PASSWORD", "otra-de-prueba");
        StandardEnvironment entorno = entorno(variables);
        assertThatCode(() -> new ConfiguracionObligatoria().postProcessEnvironment(entorno, null))
                .doesNotThrowAnyException();
        assertThat(entorno.getRequiredProperty("spring.datasource.password")).isEqualTo("otra-de-prueba");
    }

    static Stream<Arguments> secretosFaltantes() {
        return Stream.of("MYSQL_PASSWORD", "JWT_SECRET", "INTERNAL_KEY", "CIFRADO_LLAVE")
                .flatMap(variable -> Stream.of((String) null, "", "   ")
                        .map(valor -> Arguments.of(variable, valor)));
    }

    @ParameterizedTest
    @MethodSource("secretosFaltantes")
    void una_variable_ausente_o_vacia_frena_el_arranque_sin_mostrar_secretos(String variable, String valor)
            throws IOException {
        Map<String, Object> variables = variables();
        if (valor == null) variables.remove(variable);
        else variables.put(variable, valor);
        StandardEnvironment entorno = entorno(variables);
        assertThatThrownBy(() -> new ConfiguracionObligatoria().postProcessEnvironment(entorno, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(variable)
                .hasMessageNotContaining("mysql-de-prueba")
                .hasMessageNotContaining("interna-de-prueba")
                .hasNoCause();
    }

    @Test
    void db_password_vacia_no_usa_la_clave_alternativa_en_silencio() throws IOException {
        Map<String, Object> variables = variables();
        variables.put("DB_PASSWORD", "");
        StandardEnvironment entorno = entorno(variables);
        assertThatThrownBy(() -> new ConfiguracionObligatoria().postProcessEnvironment(entorno, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_PASSWORD");
    }

    @Test
    void se_registra_despues_de_la_carga_del_env_y_antes_de_crear_los_servicios() {
        assertThat(SpringFactoriesLoader.loadFactoryNames(EnvironmentPostProcessor.class, getClass().getClassLoader()))
                .contains(ConfiguracionObligatoria.class.getName());
        assertThat(new ConfiguracionObligatoria().getOrder()).isGreaterThan(ConfigDataEnvironmentPostProcessor.ORDER);
    }

    @Test
    void rabbit_solo_exige_su_clave_cuando_esta_encendido() throws IOException {
        Map<String, Object> variables = variables();
        variables.put("EVENTS_RABBIT", "true");
        StandardEnvironment entorno = entorno(variables);
        assertThatThrownBy(() -> new ConfiguracionObligatoria().postProcessEnvironment(entorno, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RABBIT_PASSWORD");
        variables.put("RABBIT_PASSWORD", "rabbit-de-prueba");
        assertThatCode(() -> new ConfiguracionObligatoria().postProcessEnvironment(entorno, null))
                .doesNotThrowAnyException();
    }
}
