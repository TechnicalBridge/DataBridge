package com.tbridge.debt.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Cuando se le puede escribir a un deudor: de lunes a sabado, de 8:00 a 20:00,
 * y nunca un feriado (Ley 19.496, art. 37, con los cambios de la Ley 21.320).
 *
 * <p>Es la misma regla para todo lo que le escribe: la invitacion al entrar la
 * cartera, los toques de una campana, el recordatorio de una cuota y el codigo
 * que reenvia la empresa. Los feriados nacionales se calculan
 * ({@link FeriadosDeChile}); los que se decretan (elecciones, plebiscitos,
 * feriados especiales) no se pueden calcular y vienen de
 * {@code CONTACTO_FERIADOS}.
 */
@Component
public class HorarioDeContacto {

    private static final Logger log = LoggerFactory.getLogger(HorarioDeContacto.class);

    /** La hora que cuenta es la de Chile, este donde este el servidor. */
    public static final ZoneId CHILE = ZoneId.of("America/Santiago");

    /** La regla, como se le dice a una persona. */
    public static final String REGLA = "de lunes a sabado, de 8:00 a 20:00, y nunca un feriado";

    private static final LocalTime DESDE = LocalTime.of(8, 0);
    private static final LocalTime HASTA = LocalTime.of(20, 0);

    /** Los que se decretan: no se pueden calcular. */
    private final Set<LocalDate> decretados;
    /** Los nacionales, calculados una vez por ano. */
    private final Map<Integer, Set<LocalDate>> nacionales = new ConcurrentHashMap<>();

    public HorarioDeContacto(@Value("${app.contacto.feriados:}") String decretados) {
        this.decretados = Arrays.stream(decretados.split(","))
                .map(String::trim)
                .filter(dia -> !dia.isEmpty())
                .map(LocalDate::parse)
                .collect(Collectors.toUnmodifiableSet());
        int ano = LocalDate.now(CHILE).getYear();
        log.info("Feriados de {} en que no se le escribe a ningun deudor: {}; decretados (CONTACTO_FERIADOS): {}",
                ano, new TreeSet<>(nacionalesDe(ano)), new TreeSet<>(this.decretados));
    }

    /** Si a esta hora de Chile se puede hacer cobranza. */
    public boolean permite(LocalDateTime ahora) {
        LocalTime hora = ahora.toLocalTime();
        return esDiaDeContacto(ahora.toLocalDate()) && !hora.isBefore(DESDE) && hora.isBefore(HASTA);
    }

    /** Si ahora mismo, en Chile, se puede hacer cobranza. */
    public boolean permiteAhora() {
        return permite(LocalDateTime.now(CHILE));
    }

    /** Si ese dia se puede escribir a alguna hora: ni domingo ni feriado. */
    public boolean esDiaDeContacto(LocalDate dia) {
        return dia.getDayOfWeek() != DayOfWeek.SUNDAY && !esFeriado(dia);
    }

    /** Un feriado nacional de cualquier ano, o uno decretado. */
    boolean esFeriado(LocalDate dia) {
        return decretados.contains(dia) || nacionalesDe(dia.getYear()).contains(dia);
    }

    private Set<LocalDate> nacionalesDe(int ano) {
        return nacionales.computeIfAbsent(ano, FeriadosDeChile::delAno);
    }
}
