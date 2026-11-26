package com.tbridge.auth.service;

import com.tbridge.auth.dto.response.SesionAbiertaResponse;
import com.tbridge.auth.dto.response.SesionCerradaResponse;
import com.tbridge.auth.exception.ApiException;
import com.tbridge.auth.model.AdminLog;
import com.tbridge.auth.model.Session;
import com.tbridge.auth.model.StaffUser;
import com.tbridge.auth.repository.AdminLogRepository;
import com.tbridge.auth.repository.SessionRepository;
import com.tbridge.auth.repository.StaffUserRepository;
import com.tbridge.auth.security.JwtPrincipal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Las sesiones abiertas del personal, para que el admin de DataBridge las vea y
 * las cierre a distancia (#439): a una persona que deja la empresa o que perdio
 * su equipo.
 *
 * <p>Cerrar una sesion revoca su familia de llaves de renovacion, como cuando se
 * detecta una llave robada. El JWT que la persona ya tiene no se revoca (#428):
 * sirve hasta que vence, a lo mas los minutos de {@code jwt.ttl-minutes}. Las
 * sesiones de los deudores no salen ni se cierran desde aqui.
 */
@Service
public class SesionesDelPersonal {

    static final int MAXIMO_POR_PAGINA = 50;
    private static final Set<Session.Role> DEL_PERSONAL = Set.of(Session.Role.CREDITOR, Session.Role.ADMIN);

    private final SessionRepository sesiones;
    private final StaffUserRepository personal;
    private final AdminLogRepository bitacora;
    private final Duration vidaDelJwt;
    private final Clock reloj;

    @Autowired
    public SesionesDelPersonal(SessionRepository sesiones, StaffUserRepository personal, AdminLogRepository bitacora,
                               @Value("${jwt.ttl-minutes:15}") long minutosDelJwt) {
        this(sesiones, personal, bitacora, Duration.ofMinutes(minutosDelJwt), Clock.systemUTC());
    }

    /** Para las pruebas, que necesitan fijar el reloj. */
    SesionesDelPersonal(SessionRepository sesiones, StaffUserRepository personal, AdminLogRepository bitacora,
                        Duration vidaDelJwt, Clock reloj) {
        this.sesiones = sesiones;
        this.personal = personal;
        this.bitacora = bitacora;
        this.vidaDelJwt = vidaDelJwt;
        this.reloj = reloj;
    }

    /**
     * Las sesiones que todavia se pueden renovar, la que se renovo hace menos
     * primero: es lo mas parecido a "lo ultimo que hizo" que guarda ms-auth.
     */
    @Transactional(readOnly = true)
    public Page<SesionAbiertaResponse> abiertas(int pagina, int porPagina, JwtPrincipal admin) {
        if (pagina < 0 || porPagina < 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "La página y su tamaño deben ser positivos");
        }
        PageRequest orden = PageRequest.of(pagina, Math.min(porPagina, MAXIMO_POR_PAGINA),
                Sort.by(Sort.Order.desc("issuedAt"), Sort.Order.desc("id")));
        Page<Session> vivas = sesiones.abiertas(DEL_PERSONAL, reloj.instant(), orden);
        if (vivas.isEmpty()) {
            return vivas.map(sesion -> respuesta(sesion, Map.of(), Map.of(), admin));
        }

        //  Dos consultas para la pagina entera, no dos por fila.
        Set<String> familias = vivas.stream().map(Session::getFamilyId).collect(Collectors.toSet());
        Map<String, Instant> inicios = sesiones.iniciosDe(familias).stream()
                .collect(Collectors.toMap(SessionRepository.InicioDeFamilia::getFamilia,
                        SessionRepository.InicioDeFamilia::getInicio));
        Set<String> correos = vivas.stream().map(Session::getEmail).collect(Collectors.toSet());
        Map<String, StaffUser> personas = personal.findByEmailIn(correos).stream()
                .collect(Collectors.toMap(StaffUser::getEmail, Function.identity(), (una, otra) -> una));
        return vivas.map(sesion -> respuesta(sesion, inicios, personas, admin));
    }

    /**
     * Cierra una sesion: su siguiente renovacion responde 401 y la persona
     * tiene que volver a entrar. Queda en la bitacora del panel.
     */
    @Transactional
    public SesionCerradaResponse cerrar(String familia, JwtPrincipal admin) {
        Session ultima = sesiones.findFirstByFamilyIdOrderByIdDesc(familia)
                .filter(sesion -> DEL_PERSONAL.contains(sesion.getRole()))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                        "No hay ninguna sesión del personal con ese id"));
        Instant ahora = reloj.instant();
        if (ultima.revocada() || ultima.rotada() || ultima.vencida(ahora)) {
            throw new ApiException(HttpStatus.CONFLICT, "Esa sesión ya está cerrada");
        }

        sesiones.revocarFamilia(familia, ahora);
        String empresa = personal.findByEmailIgnoreCase(ultima.getEmail()).map(StaffUser::getOrgRut).orElse(null);
        bitacora.save(AdminLog.de(admin.email(), AdminLog.Action.sesion_cerrada, empresa, ultima.getEmail()));
        return new SesionCerradaResponse(familia, ultima.getEmail(), ahora.plus(vidaDelJwt));
    }

    private SesionAbiertaResponse respuesta(Session sesion, Map<String, Instant> inicios,
                                            Map<String, StaffUser> personas, JwtPrincipal admin) {
        StaffUser persona = personas.get(sesion.getEmail());
        Instant desde = inicios.getOrDefault(sesion.getFamilyId(), sesion.getIssuedAt());
        //  La primera llave de la familia es la de entrar: si es la viva, no ha renovado.
        Instant renovada = sesion.getIssuedAt().equals(desde) ? null : sesion.getIssuedAt();
        String rol = sesion.getRole() == Session.Role.ADMIN ? "admin" : "usuario";
        boolean tuya = admin.email() != null && admin.email().equalsIgnoreCase(sesion.getEmail());
        return new SesionAbiertaResponse(sesion.getFamilyId(), persona == null ? null : persona.getFullName(),
                sesion.getEmail(), rol, persona == null ? null : persona.getOrgRut(), desde, renovada,
                sesion.getExpiresAt(), tuya, vidaDelJwt.toMinutes());
    }
}
