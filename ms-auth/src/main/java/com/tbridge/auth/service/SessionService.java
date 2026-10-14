package com.tbridge.auth.service;

import com.tbridge.auth.model.Session;
import com.tbridge.auth.repository.SessionRepository;
import com.tbridge.auth.exception.ApiException;
import com.tbridge.auth.util.Hash;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Las llaves de renovacion: lo que mantiene a alguien adentro mas alla de los
 * quince minutos del JWT, y lo unico de la sesion que se puede revocar.
 *
 * <p>Las reglas estan explicadas en {@code V2__sesiones.sql}. En corto: cada
 * uso jubila la llave y deja otra en la misma familia; cerrar sesion revoca la
 * familia; y una llave jubilada que vuelve a aparecer significa que alguien
 * mas la tiene, asi que tambien se revoca la familia.
 */
@Service
public class SessionService {

    /**
     * Una llave recien jubilada que vuelve dentro de este margen es de otra
     * pestana del mismo navegador, no de un ladron.
     *
     * <p>La cookie es una sola para todas las pestanas. Si dos renuevan a la
     * vez, la primera cambia la llave y el navegador ya guarda la nueva; la
     * segunda venia en camino con la vieja. Tratarla como robo cerraria la
     * sesion de alguien que solo tenia dos pestanas abiertas. Se le responde
     * "reintenta", y al reintentar el navegador ya manda la llave nueva.
     */
    static final Duration GRACIA = Duration.ofSeconds(30);

    private static final SecureRandom AZAR = new SecureRandom();

    private final SessionRepository sesiones;
    private final Duration vida;
    private final Clock reloj;

    /** La llave en claro: se entrega una vez y en la base queda solo su huella. */
    public record Llave(String valor, Instant venceEn) {}

    /** Lo que sale de renovar: la llave nueva y a quien pertenece. */
    public record Renovada(Llave llave, Session.Role role, String rut, String email, String familia) {}

    @Autowired
    public SessionService(SessionRepository sesiones,
                          @Value("${app.refresh-ttl-hours:168}") long horas) {
        this(sesiones, Duration.ofHours(horas), Clock.systemUTC());
    }

    /** Para las pruebas, que necesitan mover el reloj. */
    SessionService(SessionRepository sesiones, Duration vida, Clock reloj) {
        if (vida.isZero() || vida.isNegative()) {
            throw new IllegalStateException("app.refresh-ttl-hours tiene que ser al menos 1");
        }
        this.sesiones = sesiones;
        this.vida = vida;
        this.reloj = reloj;
    }

    /** Un inicio de sesion: una familia nueva con su primera llave. */
    @Transactional
    public Llave abrir(Session.Role role, String rut, String email, String ipHash) {
        String familia = UUID.randomUUID().toString();
        return emitir(familia, role, rut, email, reloj.instant().plus(vida), ipHash);
    }

    /**
     * Cambia una llave por la siguiente.
     *
     * <p>{@code noRollbackFor}: cuando se detecta el reuso, la familia se
     * revoca y DESPUES se lanza el error. Sin esto, el error desharia la
     * revocacion y el ladron seguiria adentro.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Renovada renovar(String valor, String ipHash) {
        Session actual = sesiones.bloquearPorHuella(huella(valor)).orElseThrow(SessionService::invalida);
        Instant ahora = reloj.instant();

        if (actual.revocada()) {
            throw invalida();
        }
        if (actual.rotada()) {
            if (Duration.between(actual.getRotatedAt(), ahora).compareTo(GRACIA) < 0) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "La sesion se acaba de renovar en otra pestana. Reintenta.");
            }
            //  Alguien mas tiene esta llave: el dueno legitimo ya la habia
            //  cambiado por otra. Se corta todo, a los dos.
            sesiones.revocarFamilia(actual.getFamilyId(), ahora);
            throw new ApiException(HttpStatus.UNAUTHORIZED,
                    "La sesion se cerro por seguridad. Vuelve a entrar.");
        }
        if (actual.vencida(ahora)) {
            throw invalida();
        }

        actual.setRotatedAt(ahora);
        sesiones.save(actual);
        //  La hija hereda el vencimiento de la familia: renovar no alarga la
        //  vida de la sesion, solo la mantiene dentro de lo que ya tenia.
        Llave nueva = emitir(actual.getFamilyId(), actual.getRole(), actual.getRut(), actual.getEmail(),
                actual.getExpiresAt(), ipHash);
        return new Renovada(nueva, actual.getRole(), actual.getRut(), actual.getEmail(), actual.getFamilyId());
    }

    /** Cerrar sesion: revoca la familia entera, no solo esta llave. */
    @Transactional
    public void cerrar(String valor) {
        sesiones.findByRefreshHash(huella(valor))
                .ifPresent(sesion -> sesiones.revocarFamilia(sesion.getFamilyId(), reloj.instant()));
    }

    @Transactional
    public void revocarFamilia(String familia) {
        sesiones.revocarFamilia(familia, reloj.instant());
    }

    /**
     * Cierra todas las sesiones de una persona, en todos sus navegadores. Es
     * lo que pasa cuando un admin le quita el acceso: su JWT vigente dura a lo
     * mas quince minutos, y despues ya no tiene con que renovarlo.
     *
     * @return cuantas llaves se revocaron.
     */
    @Transactional
    public int revocarDe(String correo) {
        return sesiones.revocarDeCorreo(correo, reloj.instant());
    }

    private Llave emitir(String familia, Session.Role role, String rut, String email,
                         Instant venceEn, String ipHash) {
        //  256 bits al azar: adivinarla no es un problema de intentos por
        //  minuto, es un problema de la edad del universo.
        byte[] bytes = new byte[32];
        AZAR.nextBytes(bytes);
        String valor = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);

        Session sesion = new Session();
        sesion.setFamilyId(familia);
        sesion.setRefreshHash(huella(valor));
        sesion.setRole(role);
        sesion.setRut(rut);
        sesion.setEmail(email);
        sesion.setIssuedAt(reloj.instant());
        sesion.setExpiresAt(venceEn);
        sesion.setIpHash(ipHash);
        sesiones.save(sesion);
        return new Llave(valor, venceEn);
    }

    /**
     * El mismo mensaje para "no existe", "vencida" y "revocada": distinguirlas
     * le diria a quien prueba llaves cual de sus intentos estuvo cerca.
     */
    private static ApiException invalida() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "La sesion no es valida. Vuelve a entrar.");
    }

    static String huella(String valor) {
        return Hash.sha256(valor);
    }
}
