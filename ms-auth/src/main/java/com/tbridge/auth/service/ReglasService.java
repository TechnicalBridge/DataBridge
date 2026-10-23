package com.tbridge.auth.service;

import com.tbridge.auth.dto.response.ReglasResponse;
import com.tbridge.auth.dto.response.ReglasResponse.Grupo;
import com.tbridge.auth.dto.response.ReglasResponse.Regla;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Las reglas de acceso y de correo que rigen en ms-auth, para la pantalla
 * Reglas del panel: cuanto dura cada cosa y por donde salen los correos. De
 * los correos dice por donde y en que modo, nunca una clave ni una direccion
 * entera.
 */
@Service
public class ReglasService {

    private final long codigoHoras;
    private final long enlaceMinutos;
    private final long invitacionHoras;
    private final long sesionHoras;
    private final long tokenMinutos;
    private final MailService correo;

    public ReglasService(@Value("${app.code-ttl-hours:24}") long codigoHoras,
                         @Value("${app.magic-ttl-minutes:15}") long enlaceMinutos,
                         @Value("${app.invitation-ttl-hours:24}") long invitacionHoras,
                         @Value("${app.refresh-ttl-hours:168}") long sesionHoras,
                         @Value("${jwt.ttl-minutes:15}") long tokenMinutos,
                         MailService correo) {
        this.codigoHoras = codigoHoras;
        this.enlaceMinutos = enlaceMinutos;
        this.invitacionHoras = invitacionHoras;
        this.sesionHoras = sesionHoras;
        this.tokenMinutos = tokenMinutos;
        this.correo = correo;
    }

    public ReglasResponse reglas() {
        Grupo acceso = new Grupo("Acceso", List.of(
                Regla.de("El código de acceso del deudor dura", horas(codigoHoras), "CODE_TTL_HOURS"),
                Regla.de("El enlace para entrar dura", minutos(enlaceMinutos), "MAGIC_TTL_MINUTES"),
                Regla.de("La invitación de una persona nueva dura", horas(invitacionHoras), "INVITACION_TTL_HORAS"),
                Regla.de("Una sesión dura", horas(sesionHoras) + " desde que se entra, se use o no",
                        "REFRESH_TTL_HOURS"),
                Regla.de("El token de la sesión se renueva cada", minutos(tokenMinutos), "JWT_TTL_MINUTES")));
        MailService.ComoSalen como = correo.comoSalen();
        Grupo correos = new Grupo("Correos", List.of(
                Regla.de("Por dónde salen", como.por(), como.variables()),
                Regla.de("Modo de prueba", como.modo(), "CORREO_SOLO_A · CORREO_DESVIAR_A"),
                Regla.de("Quién los firma", como.remitente(), "MAIL_FROM · MAIL_FROM_NAME")));
        return new ReglasResponse("ms-auth", List.of(acceso, correos));
    }

    /** 24 horas; 168 horas son 7 días. */
    static String horas(long horas) {
        if (horas >= 48 && horas % 24 == 0) {
            return horas / 24 + " días";
        }
        return horas + (horas == 1 ? " hora" : " horas");
    }

    static String minutos(long minutos) {
        return minutos + (minutos == 1 ? " minuto" : " minutos");
    }
}
