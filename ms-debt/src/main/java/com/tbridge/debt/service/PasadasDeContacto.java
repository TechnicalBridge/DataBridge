package com.tbridge.debt.service;

import com.tbridge.debt.dto.response.ContactoEstadoResponse;
import com.tbridge.debt.dto.response.ContactosResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.support.CronExpression;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Las pasadas de contacto, para el panel (#441): cuando fue la ultima, que hizo
 * o con que error se cayo, cuando toca la proxima, y si el horario y el limite
 * de la ley estan activos.
 *
 * <p>Se guarda en memoria, sin tocar la base: al reiniciar ms-debt se empieza de
 * nuevo, y la respuesta dice desde cuando cuenta. Asi, una pasada que se cae
 * como en #338 se ve desde el panel, y no solo en el registro del servicio.
 */
@Component
public class PasadasDeContacto {

    /** Lo que puede tardar en partir una pasada antes de darla por atrasada. */
    static final Duration MARGEN = Duration.ofMinutes(2);

    private final CronExpression cron;
    private final HorarioDeContacto horario;
    private final LimiteDeContacto limite;
    private final Clock reloj;
    private final Instant arranco;
    private final AtomicReference<ContactoEstadoResponse.Pasada> ultima = new AtomicReference<>();
    private final AtomicReference<Instant> enCurso = new AtomicReference<>();

    @Autowired
    public PasadasDeContacto(@Value("${app.contacto.cron:0 */15 * * * *}") String expresion,
                             HorarioDeContacto horario, LimiteDeContacto limite) {
        this(expresion, horario, limite, Clock.systemUTC());
    }

    /** Para las pruebas, que necesitan mover el reloj. */
    PasadasDeContacto(String expresion, HorarioDeContacto horario, LimiteDeContacto limite, Clock reloj) {
        this.cron = CronExpression.parse(expresion);
        this.horario = horario;
        this.limite = limite;
        this.reloj = reloj;
        this.arranco = reloj.instant();
    }

    public void empezo() {
        enCurso.set(reloj.instant());
    }

    public void termino(ContactosResponse hecho) {
        ultima.set(new ContactoEstadoResponse.Pasada(inicio(), reloj.instant(), hecho.enHorario(),
                hecho.enviados(), hecho.invitados(), hecho.omitidos(), null));
    }

    /** Se cayo: queda el tipo de error, no su mensaje, que puede traer datos de una deuda. */
    public void fallo(Throwable error) {
        ultima.set(new ContactoEstadoResponse.Pasada(inicio(), reloj.instant(), false, 0, 0, 0,
                error.getClass().getSimpleName()));
    }

    public ContactoEstadoResponse estado() {
        Instant ahora = reloj.instant();
        ContactoEstadoResponse.Pasada laUltima = ultima.get();
        Instant corriendo = enCurso.get();
        //  La que debio partir despues de la ultima (o del arranque): si ya paso de largo, la tarea se detuvo.
        //  Spring a veces dispara unos milisegundos antes de la hora (a las 23:59:59,960 la de las 00:00): sin
        //  el segundo de mas, la siguiente seria esa misma, y a los dos minutos la daria por atrasada.
        Instant esperada = siguiente((laUltima != null ? laUltima.inicio() : arranco).plusSeconds(1));
        boolean atrasada = corriendo == null && esperada != null && ahora.isAfter(esperada.plus(MARGEN));
        return new ContactoEstadoResponse(arranco, laUltima, corriendo, siguiente(ahora), atrasada,
                !horario.apagado(), !limite.apagado());
    }

    private Instant inicio() {
        Instant inicio = enCurso.getAndSet(null);
        return inicio != null ? inicio : reloj.instant();
    }

    private Instant siguiente(Instant desde) {
        ZonedDateTime proxima = cron.next(desde.atZone(HorarioDeContacto.CHILE));
        return proxima == null ? null : proxima.toInstant();
    }
}
