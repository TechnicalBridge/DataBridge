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
import com.tbridge.auth.util.Correo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Como entra cada quien.
 *
 * <p>Los tres portales entran primero por codigo: RUT para el deudor,
 * correo para el personal. El respaldo solicitado incluye codigo y enlace
 * en un solo mensaje; ambos consumen el mismo acceso, no son dos factores.
 * Los codigos emitidos por campana conservan su vigencia propia.
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
     * Los rechazos conservan el contador, y el bloqueo de la fila evita que
     * dos peticiones consuman a la vez el mismo codigo o su enlace hermano.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public Sesion entrarConCodigo(String rutCrudo, String codigoCrudo, String ip) {
        String ipHash = ip == null ? null : Hash.sha256(ip);
        frenarFuerzaBruta(ipHash);

        String rut = normalizarRut(rutCrudo);
        String codigo = normalizarCodigo(codigoCrudo);

        MagicLink pedido = enlaces.findFirstByDebtorRutAndCodeHashIsNotNullOrderByIssuedAtDescIdDesc(rut)
                .orElse(null);

        AccessCode registro = codigos
                .findFirstByDebtorRutOrderByIssuedAtDescIdDesc(rut)
                .orElse(null);
        if (pedido != null && (registro == null || !pedido.getIssuedAt().isBefore(registro.getIssuedAt()))) {
            return consumirCodigoPedido(pedido, codigo, ipHash);
        }

        //  El mismo mensaje para "no hay codigo" y para "no corresponde": si
        //  fueran distintos, se podria averiguar que RUT tienen deuda.
        if (registro == null || registro.getConsumedAt() != null) {
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

        return sesionDeDeudor(rut, ipHash);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Sesion entrarConCodigoDePersonal(String email, String codigo, String ip) {
        String direccion = correoValido(email);
        String ipHash = ip == null ? null : Hash.sha256(ip);
        frenarFuerzaBruta(ipHash);
        MagicLink pedido = enlaces
                .findFirstByEmailIgnoreCaseAndDebtorRutIsNullAndCodeHashIsNotNullOrderByIssuedAtDescIdDesc(direccion)
                .orElse(null);
        if (pedido == null) {
            anotar(null, AccessLog.Method.code, AccessLog.Outcome.invalid, ipHash);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "El código no corresponde a esos datos");
        }
        return consumirCodigoPedido(pedido, normalizarCodigo(codigo), ipHash);
    }

    private Sesion consumirCodigoPedido(MagicLink pedido, String codigo, String ipHash) {
        String rut = pedido.getDebtorRut();
        if (pedido.getAttempts() >= pedido.getMaxAttempts()) {
            anotar(rut, AccessLog.Method.code, AccessLog.Outcome.exhausted, ipHash);
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Demasiados intentos con ese código. Pide uno nuevo.");
        }
        if (!pedido.vigente()) {
            anotar(rut, AccessLog.Method.code, AccessLog.Outcome.invalid, ipHash);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "El código no corresponde, venció o ya se usó");
        }
        String identidad = rut == null ? pedido.getEmail() : rut;
        if (!Hash.igualesEnTiempoConstante(pedido.getCodeHash(), huella(identidad, codigo))) {
            pedido.fallo();
            enlaces.save(pedido);
            anotar(rut, AccessLog.Method.code, AccessLog.Outcome.invalid, ipHash);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "El código no corresponde a esos datos");
        }
        pedido.setConsumedAt(Instant.now());
        enlaces.save(pedido);
        return abrirSesionDePedido(pedido, AccessLog.Method.code, ipHash);
    }

    // ------------------------------------------------------------------
    //  El respaldo: enlace de un solo uso
    // ------------------------------------------------------------------

    /** Lo que se le responde a quien pide un enlace, salga o no: no revela quien es deudor ni quien es personal. */
    static final String RESPUESTA_ENLACE = "Si tus datos están registrados, recibirás un código y un enlace en el mismo correo.";
    static final String RESPUESTA_CODIGO = "Si tus datos están registrados, recibirás un código de acceso por correo.";

    /**
     * El camino de excepcion, para quien no logra entrar con el codigo.
     *
     * <p>Se conserva a sabiendas de que es el patron que el caso critica. Por
     * eso dura quince minutos y no veinticuatro horas, y nunca va a un correo
     * que elija quien lo pide:
     * <ul>
     *   <li><b>Con RUT</b> es un deudor: el mensaje va solo al correo que
     *       registro el acreedor, que se le pregunta a ms-debt. El que se
     *       escribe no se usa. Sin correo registrado, no sale nada.</li>
     *   <li><b>Sin RUT</b> es el personal de una empresa: sale solo si el
     *       correo es de una cuenta habilitada.</li>
     * </ul>
     * La respuesta no revela si existe la identidad. El envio se agenda en
     * segundo plano despues de guardar; sent indica que se agendo, no entrega.
     */
    @Transactional
    public EnlaceResponse pedirEnlace(String rutCrudo, String email, String ip) {
        return pedirAcceso(rutCrudo, email, ip, true);
    }

    @Transactional
    public EnlaceResponse pedirCodigo(String rutCrudo, String email, String ip) {
        return pedirAcceso(rutCrudo, email, ip, false);
    }

    private EnlaceResponse pedirAcceso(String rutCrudo, String email, String ip, boolean conEnlace) {
        String ipHash = ip == null ? null : Hash.sha256(ip);
        frenarFuerzaBruta(ipHash);
        String rut = rutCrudo == null || rutCrudo.isBlank() ? null : normalizarRut(rutCrudo);
        AccessLog.Method metodo = conEnlace ? AccessLog.Method.magic_link : AccessLog.Method.code;
        String destino;
        if (rut != null) {
            destino = deudas.correoDelDeudor(rut).map(c -> c.trim().toLowerCase(Locale.ROOT)).orElse(null);
        } else {
            destino = personal.findByEmailIgnoreCase(correoValido(email))
                    .filter(StaffUser::habilitado)
                    .map(s -> s.getEmail().toLowerCase(Locale.ROOT))
                    .orElse(null);
        }

        if (destino == null) {
            anotar(rut, metodo, AccessLog.Outcome.refused, ipHash);
        } else {
            if (rut == null) enlaces.anularPedidosDeCorreo(destino, Instant.now());
            else enlaces.anularPedidosDeRut(rut, Instant.now());
            String token = UUID.randomUUID().toString();
            String codigo = generarCodigo();
            MagicLink enlace = new MagicLink();
            enlace.setTokenHash(Hash.sha256(token));
            enlace.setDebtorRut(rut);
            enlace.setEmail(destino);
            enlace.setExpiresAt(Instant.now().plus(magicTtlMinutes, ChronoUnit.MINUTES));
            enlace.setCodeHash(huella(rut == null ? destino : rut, codigo));
            enlace.setLinkEnabled(conEnlace);
            enlaces.save(enlace);
            Runnable enviar = () -> correo.enviarAcceso(destino, codigo,
                    conEnlace ? publicUrl + "/magic?token=" + token : null, magicTtlMinutes);
            //  No entregar credenciales de una transaccion que aun puede fallar.
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override public void afterCommit() { enviar.run(); }
                });
            } else {
                enviar.run();
            }
            anotar(rut, metodo, AccessLog.Outcome.sent, ipHash);
        }
        return new EnlaceResponse(true, conEnlace ? RESPUESTA_ENLACE : RESPUESTA_CODIGO, magicTtlMinutes);
    }

    @Transactional(noRollbackFor = ApiException.class)
    public Sesion entrarConEnlace(String token, String ip) {
        String ipHash = ip == null ? null : Hash.sha256(ip);
        MagicLink enlace = enlaces.findByTokenHash(Hash.sha256(token == null ? "" : token.trim()))
                .orElse(null);
        if (enlace == null || !enlace.isLinkEnabled() || !enlace.vigente()) {
            anotar(enlace == null ? null : enlace.getDebtorRut(),
                    AccessLog.Method.magic_link, AccessLog.Outcome.invalid, ipHash);
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Enlace invalido o vencido");
        }
        enlace.setConsumedAt(Instant.now());
        enlaces.save(enlace);
        return abrirSesionDePedido(enlace, AccessLog.Method.magic_link, ipHash);
    }

    private Sesion abrirSesionDePedido(MagicLink pedido, AccessLog.Method metodo, String ipHash) {
        try {
            Sesion sesion = sesionDePedido(pedido, ipHash);
            anotar(pedido.getDebtorRut(), metodo, AccessLog.Outcome.granted, ipHash);
            return sesion;
        } catch (ApiException ex) {
            anotar(pedido.getDebtorRut(), metodo, AccessLog.Outcome.refused, ipHash);
            throw ex;
        }
    }

    private Sesion sesionDePedido(MagicLink enlace, String ipHash) {
        //  Un enlace pedido con RUT es del deudor, y abre solo su sesion: aunque
        //  su correo fuera tambien el de alguien del personal, no entra como empresa.
        if (enlace.getDebtorRut() != null) {
            return sesionDeDeudor(enlace.getDebtorRut(), ipHash);
        }
        //  Codigo o enlace no reemplazan la habilitacion actual del personal.
        StaffUser staff = personal.findByEmailIgnoreCase(enlace.getEmail()).orElse(null);
        if (staff == null || !staff.habilitado()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Ese correo no tiene acceso");
        }
        if (!staff.esAdmin() && deudas.empresa(staff.getOrgRut()).filter(DeudasClient.Empresa::activa).isEmpty()) {
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

    private static String correoValido(String email) {
        return Correo.normalizar(email).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "Correo no valido"));
    }

    private static String normalizarCodigo(String codigo) {
        return (codigo == null ? "" : codigo).trim().toUpperCase(Locale.ROOT).replace(" ", "").replace("-", "");
    }
}
