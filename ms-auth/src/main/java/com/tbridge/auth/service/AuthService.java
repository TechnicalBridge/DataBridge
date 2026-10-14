package com.tbridge.auth.service;

import com.tbridge.auth.client.DeudasClient;
import com.tbridge.auth.model.AccessCode;
import com.tbridge.auth.model.AccessLog;
import com.tbridge.auth.model.MagicLink;
import com.tbridge.auth.model.Session;
import com.tbridge.auth.model.StaffUser;
import com.tbridge.auth.dto.response.CodigoEmitidoResponse;
import com.tbridge.auth.dto.response.EnlaceResponse;
import com.tbridge.auth.dto.response.UsuarioResponse;
import com.tbridge.auth.repository.AccessCodeRepository;
import com.tbridge.auth.repository.AccessLogRepository;
import com.tbridge.auth.repository.MagicLinkRepository;
import com.tbridge.auth.repository.StaffUserRepository;
import com.tbridge.auth.security.JwtService;
import com.tbridge.auth.exception.ApiException;
import com.tbridge.auth.util.Hash;
import com.tbridge.auth.util.Rut;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Como entra cada quien.
 *
 * <p><b>El deudor entra con un codigo y sin cuenta.</b> No hay registro, no
 * hay contrasena. El codigo le llega por WhatsApp y por su correo registrado,
 * y el entra escribiendo la direccion del portal por su cuenta.
 *
 * <p>Por que importa: un phisher necesita que hagas clic en SU enlace. Si el
 * mensaje te manda a un sitio que puedes escribir, buscar o verificar antes de
 * entrar, el atacante pierde el control del destino, que era todo lo que
 * tenia. Es la separacion entre <i>donde vas</i> y <i>quien te escribio</i>,
 * que es la definicion de verificacion fuera de banda.
 *
 * <p><b>El RUT tambien se pide.</b> No es friccion gratuita: el codigo es
 * corto para poder dictarlo, y sin un segundo dato, probar codigos al azar
 * seria barato.
 */
@Service
public class AuthService {

    /**
     * Alfabeto del codigo: sin 0/O ni 1/I/L.
     *
     * Un codigo se lee en un mensaje y a veces se dicta por telefono. Cada par
     * ambiguo es un deudor que escribe mal, falla y desconfia.
     */
    private static final String ALFABETO = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private static final int LARGO_CODIGO = 6;

    private static final SecureRandom AZAR = new SecureRandom();

    /** Mas de esto desde un mismo origen en diez minutos huele a fuerza bruta. */
    private static final int INTENTOS_POR_ORIGEN = 10;

    private final AccessCodeRepository codigos;
    private final MagicLinkRepository enlaces;
    private final StaffUserRepository personal;
    private final AccessLogRepository bitacora;
    private final JwtService jwt;
    private final SessionService sesiones;
    private final MailService correo;
    private final DeudasClient deudas;
    private final String publicUrl;
    private final long codeTtlHours;
    private final long magicTtlMinutes;

    /**
     * Una sesion recien abierta o renovada.
     *
     * <p>El JWT va en el cuerpo de la respuesta y la llave de renovacion en una
     * cookie: por eso viajan separados, y el controlador arma cada uno.
     */
    public record Sesion(String token, UsuarioResponse user, SessionService.Llave llave) {}

    public AuthService(
            AccessCodeRepository codigos,
            MagicLinkRepository enlaces,
            StaffUserRepository personal,
            AccessLogRepository bitacora,
            JwtService jwt,
            SessionService sesiones,
            MailService correo,
            DeudasClient deudas,
            @Value("${app.public-url}") String publicUrl,
            @Value("${app.code-ttl-hours:24}") long codeTtlHours,
            @Value("${app.magic-ttl-minutes:15}") long magicTtlMinutes
    ) {
        this.codigos = codigos;
        this.enlaces = enlaces;
        this.personal = personal;
        this.bitacora = bitacora;
        this.jwt = jwt;
        this.sesiones = sesiones;
        this.correo = correo;
        this.deudas = deudas;
        this.publicUrl = publicUrl.replaceAll("/$", "");
        this.codeTtlHours = codeTtlHours;
        this.magicTtlMinutes = magicTtlMinutes;
    }

    // ------------------------------------------------------------------
    //  Emitir el codigo (lo pide el motor de campana, no el deudor)
    // ------------------------------------------------------------------

    /**
     * Emite un codigo para un RUT y lo devuelve UNA vez.
     *
     * <p>Quien llama es el motor de campana, que lo manda por los canales que
     * correspondan. Despues de esta respuesta el codigo no existe en ninguna
     * parte: en la base solo queda su huella.
     */
    @Transactional
    public CodigoEmitidoResponse emitirCodigo(String rutCrudo, List<String> canales, String paraQue) {
        String rut = normalizarRut(rutCrudo);
        String codigo = generarCodigo();

        AccessCode registro = new AccessCode();
        registro.setCodeHash(huella(rut, codigo));
        registro.setDebtorRut(rut);
        registro.setChannels(canalesComoJson(canales));
        registro.setExpiresAt(Instant.now().plus(codeTtlHours, ChronoUnit.HOURS));
        registro.setIssuedFor(paraQue);
        codigos.save(registro);

        return new CodigoEmitidoResponse(codigo, rut, canales, registro.getExpiresAt(),
                "Entra a " + publicUrl + " y escribe tu RUT y el codigo");
    }

    /**
     * Anula un codigo recien emitido que nadie recibio, porque su correo no
     * salio.
     *
     * <p>No es solo orden. Para entrar vale el ultimo codigo sin usar del RUT:
     * si este quedara, el que el deudor si recibio la vez anterior dejaria de
     * servirle, por culpa de un correo que nunca vio.
     */
    @Transactional
    public void anularCodigo(String rut, String codigo) {
        codigos.deleteByCodeHash(huella(rut, codigo));
    }

    // ------------------------------------------------------------------
    //  Entrar con el codigo
    // ------------------------------------------------------------------

    /**
     * Entrar con el codigo.
     *
     * <p><b>No es transaccional a proposito.</b> Un intento fallido tiene que
     * quedar contado, y si todo el metodo fuera una transaccion, la excepcion
     * que rechaza el intento se llevaria el contador con ella: probar codigos
     * al azar saldria gratis.
     */
    public Sesion entrarConCodigo(String rutCrudo, String codigoCrudo, String ip) {
        String ipHash = ip == null ? null : Hash.sha256(ip);
        frenarFuerzaBruta(ipHash);

        String rut = normalizarRut(rutCrudo);
        String codigo = (codigoCrudo == null ? "" : codigoCrudo)
                .trim().toUpperCase(Locale.ROOT).replace(" ", "").replace("-", "");

        AccessCode registro = codigos
                .findFirstByDebtorRutAndConsumedAtIsNullOrderByIssuedAtDesc(rut)
                .orElse(null);

        //  El mismo mensaje para "no hay codigo" y para "no corresponde": si
        //  fueran distintos, se podria averiguar que RUT tienen deuda.
        if (registro == null) {
            anotar(rut, AccessLog.Method.code, AccessLog.Outcome.invalid, ipHash);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "El codigo no corresponde a ese RUT");
        }
        if (registro.getAttempts() >= registro.getMaxAttempts()) {
            anotar(rut, AccessLog.Method.code, AccessLog.Outcome.exhausted, ipHash);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Demasiados intentos con ese codigo. Pide uno nuevo.");
        }
        if (Instant.now().isAfter(registro.getExpiresAt())) {
            anotar(rut, AccessLog.Method.code, AccessLog.Outcome.expired, ipHash);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "El codigo expiro. Pide uno nuevo.");
        }
        if (!Hash.igualesEnTiempoConstante(registro.getCodeHash(), huella(rut, codigo))) {
            registro.fallo();
            codigos.save(registro);
            anotar(rut, AccessLog.Method.code, AccessLog.Outcome.invalid, ipHash);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "El codigo no corresponde a ese RUT");
        }

        //  De un solo uso: consumirlo es parte de entrar.
        registro.consumir();
        codigos.save(registro);
        anotar(rut, AccessLog.Method.code, AccessLog.Outcome.granted, ipHash);

        //  La sesion se abre en su propia transaccion (SessionService), porque
        //  este metodo no tiene una a proposito: ver el comentario de arriba.
        return sesionDeDeudor(rut, ipHash);
    }

    // ------------------------------------------------------------------
    //  El respaldo: enlace de un solo uso
    // ------------------------------------------------------------------

    /** Lo que se le responde a quien pide un enlace, salga o no: no revela quien es deudor ni quien es personal. */
    static final String RESPUESTA_ENLACE = "Si tus datos estan registrados, te llegara un enlace de acceso.";

    /**
     * El camino de excepcion, para quien no logra entrar con el codigo.
     *
     * <p>Se conserva a sabiendas de que es el patron que el caso critica. Por
     * eso dura quince minutos y no veinticuatro horas, y nunca va a un correo
     * que elija quien lo pide:
     * <ul>
     *   <li><b>Con RUT</b> es un deudor: el enlace va solo al correo que
     *       registro el acreedor, que se le pregunta a ms-debt. El que se
     *       escribe no se usa. Sin correo registrado, no sale nada.</li>
     *   <li><b>Sin RUT</b> es el personal de una empresa: sale solo si el
     *       correo es de una cuenta habilitada.</li>
     * </ul>
     * La respuesta es siempre la misma, y el correo sale en segundo plano para
     * que el tiempo de respuesta tampoco lo delate. La bitacora anota si salio.
     */
    @Transactional
    public EnlaceResponse pedirEnlace(String rutCrudo, String email, String ip) {
        String ipHash = ip == null ? null : Hash.sha256(ip);
        String rut = rutCrudo == null || rutCrudo.isBlank() ? null : normalizarRut(rutCrudo);
        String destino;
        if (rut != null) {
            destino = deudas.correoDelDeudor(rut).map(c -> c.trim().toLowerCase(Locale.ROOT)).orElse(null);
        } else {
            if (email == null || !email.contains("@")) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Correo no valido");
            }
            destino = personal.findByEmailIgnoreCase(email.trim())
                    .filter(StaffUser::habilitado)
                    .map(s -> s.getEmail().toLowerCase(Locale.ROOT))
                    .orElse(null);
        }

        if (destino == null) {
            anotar(rut, AccessLog.Method.magic_link, AccessLog.Outcome.refused, ipHash);
        } else {
            String token = UUID.randomUUID().toString();
            MagicLink enlace = new MagicLink();
            enlace.setTokenHash(Hash.sha256(token));
            enlace.setDebtorRut(rut);
            enlace.setEmail(destino);
            enlace.setExpiresAt(Instant.now().plus(magicTtlMinutes, ChronoUnit.MINUTES));
            enlaces.save(enlace);
            correo.enviarEnlace(destino, publicUrl + "/magic?token=" + token, magicTtlMinutes);
            anotar(rut, AccessLog.Method.magic_link, AccessLog.Outcome.sent, ipHash);
        }
        return new EnlaceResponse(true, RESPUESTA_ENLACE, magicTtlMinutes);
    }

    @Transactional
    public Sesion entrarConEnlace(String token, String ip) {
        String ipHash = ip == null ? null : Hash.sha256(ip);
        MagicLink enlace = enlaces.findByTokenHash(Hash.sha256(token == null ? "" : token.trim()))
                .orElse(null);
        if (enlace == null || !enlace.vigente()) {
            anotar(enlace == null ? null : enlace.getDebtorRut(),
                    AccessLog.Method.magic_link, AccessLog.Outcome.invalid, ipHash);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Enlace invalido o vencido");
        }
        enlace.setConsumedAt(Instant.now());
        enlaces.save(enlace);
        anotar(enlace.getDebtorRut(), AccessLog.Method.magic_link, AccessLog.Outcome.granted, ipHash);

        //  Un enlace pedido con RUT es del deudor, y abre solo su sesion: aunque
        //  su correo fuera tambien el de alguien del personal, no entra como empresa.
        if (enlace.getDebtorRut() != null) {
            return sesionDeDeudor(enlace.getDebtorRut(), ipHash);
        }
        //  El personal de una empresa tambien entra por enlace, si sigue habilitado.
        StaffUser staff = personal.findByEmailIgnoreCase(enlace.getEmail()).orElse(null);
        if (staff == null || !staff.habilitado()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Ese correo no tiene acceso");
        }
        staff.setLastLoginAt(Instant.now());
        personal.save(staff);
        return sesionDePersonal(staff, ipHash);
    }

    // ------------------------------------------------------------------
    //  Sesiones
    // ------------------------------------------------------------------

    /**
     * Cambia la llave de renovacion por un JWT nuevo y la llave siguiente.
     *
     * <p>Al personal se lo vuelve a buscar en cada renovacion: si lo dieron de
     * baja, pierde el acceso en cuanto vence su JWT, aunque su llave siga
     * vigente. Al deudor no hay a quien darlo de baja: no tiene cuenta.
     */
    public Sesion renovar(String llave, String ip) {
        String ipHash = ip == null ? null : Hash.sha256(ip);
        SessionService.Renovada renovada = sesiones.renovar(llave, ipHash);

        if (renovada.role() == Session.Role.DEBTOR) {
            return new Sesion(jwtDeDeudor(renovada.rut()), UsuarioResponse.deudor(renovada.rut()), renovada.llave());
        }
        //  Tiene que seguir habilitado y con el rol con que abrio la sesion: una
        //  llave de admin no renueva una sesion de empresa, ni al reves.
        StaffUser staff = personal.findByEmailIgnoreCase(renovada.email()).orElse(null);
        if (staff == null || !staff.habilitado() || rolDeSesion(staff) != renovada.role()) {
            sesiones.revocarFamilia(renovada.familia());
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Ese acceso ya no esta habilitado");
        }
        return new Sesion(jwtDePersonal(staff), UsuarioResponse.personal(staff), renovada.llave());
    }

    public void cerrar(String llave) {
        sesiones.cerrar(llave);
    }

    private Sesion sesionDeDeudor(String rut, String ipHash) {
        SessionService.Llave llave = sesiones.abrir(Session.Role.DEBTOR, rut, null, ipHash);
        return new Sesion(jwtDeDeudor(rut), UsuarioResponse.deudor(rut), llave);
    }

    private Sesion sesionDePersonal(StaffUser staff, String ipHash) {
        SessionService.Llave llave = sesiones.abrir(rolDeSesion(staff), null, staff.getEmail(), ipHash);
        return new Sesion(jwtDePersonal(staff), UsuarioResponse.personal(staff), llave);
    }

    private String jwtDeDeudor(String rut) {
        return jwt.issue(rut, null, "DEBTOR", null, rut);
    }

    /**
     * El usuario de una empresa sale con el rol de siempre (CREDITOR) y el RUT
     * de su empresa. El admin de DataBridge, con un rol propio y sin RUT: asi
     * ningun servicio lo confunde con una empresa ni con un deudor.
     */
    private String jwtDePersonal(StaffUser staff) {
        return jwt.issue(String.valueOf(staff.getId()), staff.getEmail(),
                rolDeSesion(staff).name(), staff.getFullName(), staff.getOrgRut());
    }

    private static Session.Role rolDeSesion(StaffUser staff) {
        return staff.esAdmin() ? Session.Role.ADMIN : Session.Role.CREDITOR;
    }

    // ------------------------------------------------------------------
    //  Auxiliares
    // ------------------------------------------------------------------

    private void frenarFuerzaBruta(String ipHash) {
        if (ipHash == null) {
            return;
        }
        long recientes = bitacora
                .findByIpHashAndOccurredAtAfter(ipHash, Instant.now().minus(10, ChronoUnit.MINUTES))
                .stream()
                .filter(registro -> registro.getOutcome() != AccessLog.Outcome.granted)
                .count();
        if (recientes >= INTENTOS_POR_ORIGEN) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS,
                    "Demasiados intentos. Espera unos minutos.");
        }
    }

    private void anotar(String rut, AccessLog.Method metodo, AccessLog.Outcome resultado, String ipHash) {
        bitacora.save(AccessLog.de(rut, metodo, resultado, ipHash));
    }

    /** El RUT en su forma canonica, o 400 si el digito verificador no calza. */
    static String normalizarRut(String crudo) {
        String rut = Rut.normalizar(crudo);
        if (!Rut.esValido(rut)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RUT no valido");
        }
        return rut;
    }

    private static String generarCodigo() {
        StringBuilder codigo = new StringBuilder(LARGO_CODIGO);
        for (int i = 0; i < LARGO_CODIGO; i++) {
            codigo.append(ALFABETO.charAt(AZAR.nextInt(ALFABETO.length())));
        }
        return codigo.toString();
    }

    /**
     * La huella incluye el RUT.
     *
     * Asi dos deudores pueden tener el mismo codigo sin chocar en el indice
     * unico, y un codigo filtrado no sirve para el RUT de otra persona.
     */
    static String huella(String rut, String codigo) {
        return Hash.sha256(rut + ":" + codigo);
    }

    private static String canalesComoJson(List<String> canales) {
        if (canales == null || canales.isEmpty()) {
            return "[]";
        }
        return "[\"" + String.join("\",\"", canales) + "\"]";
    }
}
