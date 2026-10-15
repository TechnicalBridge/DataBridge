package com.tbridge.debt.service;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.PropertiesPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Cuando se le puede escribir a un deudor: de lunes a sabado, de 8:00 a 20:00, y nunca un feriado. */
class HorarioDeContactoTest {

    private final HorarioDeContacto horario = new HorarioDeContacto("");

    @Test
    void de_lunes_a_sabado_de_8_a_20() {
        //  El martes 6 de octubre de 2026.
        assertTrue(horario.permite(LocalDateTime.of(2026, 10, 6, 8, 0)), "a las 8:00 justo");
        assertTrue(horario.permite(LocalDateTime.of(2026, 10, 6, 19, 59)), "a las 19:59");
        assertFalse(horario.permite(LocalDateTime.of(2026, 10, 6, 7, 59)), "antes de las 8");
        assertFalse(horario.permite(LocalDateTime.of(2026, 10, 6, 20, 0)), "a las 20:00 ya no");
        assertFalse(horario.permite(LocalDateTime.of(2026, 10, 6, 1, 15)), "de madrugada");
        assertTrue(horario.permite(LocalDateTime.of(2026, 10, 10, 10, 0)), "el sabado si");
        assertFalse(horario.permite(LocalDateTime.of(2026, 10, 11, 10, 0)), "el domingo no");
    }

    @Test
    void apagado_para_probar_deja_escribir_a_cualquier_hora() {
        HorarioDeContacto apagado = new HorarioDeContacto("", true);

        assertTrue(apagado.apagado());
        assertTrue(apagado.permite(LocalDateTime.of(2026, 10, 15, 2, 30)), "de madrugada");
        assertTrue(apagado.permite(LocalDateTime.of(2026, 10, 11, 10, 0)), "un domingo");
        assertTrue(apagado.esDiaDeContacto(LocalDate.of(2026, 10, 12)), "un feriado");
        //  Los feriados se siguen sabiendo: es el horario el que no se aplica.
        assertTrue(apagado.esFeriado(LocalDate.of(2026, 10, 12)));
        assertFalse(horario.apagado(), "por omision, encendido");
    }

    @Test
    void un_dia_de_contacto_no_es_domingo_ni_feriado() {
        assertTrue(horario.esDiaDeContacto(LocalDate.of(2026, 10, 17)), "un sabado");
        assertFalse(horario.esDiaDeContacto(LocalDate.of(2026, 10, 18)), "un domingo");
        assertFalse(horario.esDiaDeContacto(LocalDate.of(2026, 10, 12)), "el Encuentro de Dos Mundos");
    }

    /**
     * Con la configuracion tal como la lee Spring Boot de application.properties
     * (con el mismo cargador), mas lo que sume CONTACTO_FERIADOS.
     */
    private static HorarioDeContacto conLaConfiguracion(String contactoFeriados) throws IOException {
        Object feriados = new PropertiesPropertySourceLoader()
                .load("configuracion", new ClassPathResource("application.properties"))
                .getFirst()
                .getProperty("app.contacto.feriados");
        return new HorarioDeContacto(String.valueOf(feriados).replace("${CONTACTO_FERIADOS:}", contactoFeriados));
    }

    @Test
    void los_feriados_se_calculan_para_cualquier_ano() throws IOException {
        HorarioDeContacto conFeriados = conLaConfiguracion("");
        //  Los de 2027, con el viernes 17 de septiembre (Ley 20.983) que faltaba en la lista a mano.
        List<LocalDate> feriados = Stream.of("2027-01-01", "2027-03-26", "2027-03-27", "2027-05-01",
                        "2027-05-21", "2027-06-21", "2027-06-28", "2027-07-16", "2027-08-15", "2027-09-17",
                        "2027-09-18", "2027-09-19", "2027-10-11", "2027-10-31", "2027-11-01", "2027-12-08",
                        "2027-12-25")
                .map(LocalDate::parse)
                .toList();

        for (LocalDate dia : feriados) {
            assertFalse(conFeriados.permite(dia.atTime(10, 0)), dia + " es feriado");
        }
        assertTrue(conFeriados.permite(LocalDateTime.of(2027, 6, 29, 10, 0)),
                "San Pedro y San Pablo cae martes y se corre al lunes 28");
        assertTrue(conFeriados.permite(LocalDateTime.of(2027, 10, 12, 10, 0)),
                "el Encuentro de Dos Mundos cae martes y se corre al lunes 11");
        assertTrue(conFeriados.permite(LocalDateTime.of(2027, 1, 4, 10, 0)), "un lunes cualquiera");
        assertFalse(conFeriados.permite(LocalDateTime.of(2031, 1, 1, 10, 0)),
                "y un ano que nadie cargo: el miercoles 1 de enero de 2031");
    }

    @Test
    void un_feriado_decretado_se_suma_a_los_calculados() throws IOException {
        HorarioDeContacto conFeriados = conLaConfiguracion("2027-11-22");

        assertFalse(conFeriados.permite(LocalDateTime.of(2027, 11, 22, 10, 0)), "el decretado");
        assertFalse(conFeriados.permite(LocalDateTime.of(2026, 10, 12, 10, 0)), "los calculados siguen");
        assertTrue(conFeriados.permite(LocalDateTime.of(2027, 11, 23, 10, 0)), "el dia siguiente, no");
    }
}
