package com.tbridge.auth.service;

import com.tbridge.auth.client.DeudasClient;
import com.tbridge.auth.dto.request.NuevaPersonaRequest;
import com.tbridge.auth.dto.response.AccionResponse;
import com.tbridge.auth.dto.response.PersonaResponse;
import com.tbridge.auth.exception.ApiException;
import com.tbridge.auth.model.AdminLog;
import com.tbridge.auth.model.MagicLink;
import com.tbridge.auth.model.StaffUser;
import com.tbridge.auth.repository.AdminLogRepository;
import com.tbridge.auth.repository.MagicLinkRepository;
import com.tbridge.auth.repository.StaffUserRepository;
import com.tbridge.auth.security.JwtPrincipal;
import com.tbridge.auth.util.Correo;
import com.tbridge.auth.util.Hash;
import com.tbridge.auth.util.Rut;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Las personas con cuenta, para el admin de DataBridge: verlas, darlas de alta
 * y quitarles o devolverles el acceso.
 *
 * <p>Antes solo existian las de los datos de ejemplo, y habilitar o
 * deshabilitar a alguien era escribir directo en la base.
 *
 * <p><b>El rol lo decide por donde se entra, no un campo.</b> Una persona que
 * se da de alta bajo una empresa es {@code usuario} de esa empresa; un admin se
 * da de alta aparte y no tiene empresa. No hay forma de pedir un admin para una
 * empresa, y un correo que ya es de una empresa no puede pasar a ser admin.
 *
 * <p>Todo lo que cambia a alguien queda anotado en {@link AdminLog}, con el
 * admin que lo hizo.
 */
@Service
public class PersonalService {

    private final StaffUserRepository personal;
    private final AdminLogRepository bitacora;
    private final MagicLinkRepository enlaces;
    private final SessionService sesiones;
    private final DeudasClient deudas;
    private final MailService correo;
    private final String publicUrl;
    private final long invitacionHoras;

    public PersonalService(
            StaffUserRepository personal,
            AdminLogRepository bitacora,
            MagicLinkRepository enlaces,
            SessionService sesiones,
            DeudasClient deudas,
            MailService correo,
            @Value("${app.public-url}") String publicUrl,
            @Value("${app.invitation-ttl-hours:24}") long invitacionHoras
    ) {
        this.personal = personal;
        this.bitacora = bitacora;
        this.enlaces = enlaces;
        this.sesiones = sesiones;
        this.deudas = deudas;
        this.correo = correo;
        this.publicUrl = publicUrl.replaceAll("/$", "");
        this.invitacionHoras = invitacionHoras;
    }

    // ------------------------------------------------------------------
    //  Ver
    // ------------------------------------------------------------------

    /** Las personas de una empresa. */
    @Transactional(readOnly = true)
    public List<PersonaResponse> deEmpresa(String rutCrudo) {
        return personal.findByOrgRutOrderByFullNameAsc(rutValido(rutCrudo)).stream()
                .map(PersonaResponse::from)
                .toList();
    }

    /** Los admin de DataBridge. */
    @Transactional(readOnly = true)
    public List<PersonaResponse> admins() {
        return personal.findByRoleOrderByFullNameAsc(StaffUser.Role.admin).stream()
                .map(PersonaResponse::from)
                .toList();
    }

    /** Lo que se hizo con las personas de una empresa, lo mas reciente primero. */
    @Transactional(readOnly = true)
    public List<AccionResponse> historialDeEmpresa(String rutCrudo) {
        return bitacora.findByOrgRutOrderByOccurredAtDesc(rutValido(rutCrudo)).stream()
                .map(AccionResponse::from)
                .toList();
    }

    /** Lo que se hizo con los admin de DataBridge. */
    @Transactional(readOnly = true)
    public List<AccionResponse> historialDeAdmins() {
        return bitacora.findByOrgRutIsNullOrderByOccurredAtDesc().stream()
                .map(AccionResponse::from)
                .toList();
    }

    // ------------------------------------------------------------------
    //  Dar de alta
    // ------------------------------------------------------------------

    /**
     * Le da acceso a una persona, como usuario de una empresa.
     *
     * <p>La empresa tiene que existir en ms-debt, que es donde vive: se le
     * pregunta antes, para no dejar a nadie colgando de una empresa que no
     * esta. A la persona le llega su invitacion al correo.
     */
    @Transactional
    public PersonaResponse crearUsuario(String rutCrudo, NuevaPersonaRequest pedido, JwtPrincipal admin) {
        String rut = rutValido(rutCrudo);
        String direccion = correoValido(pedido.correo());
        DeudasClient.Empresa empresa = deudas.empresa(rut).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "No hay ninguna empresa con ese RUT"));
        if (!empresa.activa()) {
            throw new ApiException(HttpStatus.CONFLICT, "Esa empresa esta suspendida: no se le agregan personas");
        }
        personal.findByEmailIgnoreCase(direccion).ifPresent(existente -> {
            throw new ApiException(HttpStatus.CONFLICT, existente.esAdmin()
                    ? "Ese correo es de un admin de DataBridge, y un admin no es de ninguna empresa"
                    : rut.equals(existente.getOrgRut())
                            ? "Esa persona ya esta en esta empresa"
                            : "Ese correo ya es de una persona de otra empresa");
        });

        StaffUser persona = guardar(direccion, pedido.nombre(), StaffUser.Role.usuario, rut);
        bitacora.save(AdminLog.de(admin.email(), AdminLog.Action.persona_creada, rut, direccion));
        invitar(persona, "al portal de empresas, como parte de " + empresa.nombre(), "/databridge/login");
        return PersonaResponse.from(persona);
    }

    /** Le da acceso al panel a otra persona de DataBridge. Un admin no tiene empresa. */
    @Transactional
    public PersonaResponse crearAdmin(NuevaPersonaRequest pedido, JwtPrincipal admin) {
        String direccion = correoValido(pedido.correo());
        personal.findByEmailIgnoreCase(direccion).ifPresent(existente -> {
            throw new ApiException(HttpStatus.CONFLICT, existente.esAdmin()
                    ? "Ese correo ya es de un admin de DataBridge"
                    : "Ese correo es de una persona de una empresa: no se le puede dar el rol admin");
        });

        StaffUser persona = guardar(direccion, pedido.nombre(), StaffUser.Role.admin, null);
        bitacora.save(AdminLog.de(admin.email(), AdminLog.Action.admin_creado, null, direccion));
        invitar(persona, "al panel de administración", "/admin/login");
        return PersonaResponse.from(persona);
    }

    // ------------------------------------------------------------------
    //  Quitar y devolver el acceso
    // ------------------------------------------------------------------

    /**
     * Le quita el acceso a una persona: no le llegan mas enlaces, los que tenia
     * sin abrir dejan de servir y sus sesiones se cierran. La cuenta no se
     * borra. Su JWT vigente dura, a lo mas, los quince minutos que le queden.
     *
     * <p>Un admin no se puede deshabilitar a si mismo: asi siempre queda al
     * menos uno que puede entrar al panel.
     */
    @Transactional
    public PersonaResponse deshabilitar(Long id, JwtPrincipal admin) {
        StaffUser persona = buscar(id);
        if (String.valueOf(persona.getId()).equals(admin.id())) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "No puedes quitarte el acceso a ti mismo: pidele a otro admin que lo haga");
        }
        if (!persona.habilitado()) {
            return PersonaResponse.from(persona);
        }
        persona.deshabilitar();
        personal.save(persona);
        enlaces.anularPendientesDe(persona.getEmail(), Instant.now());
        sesiones.revocarDe(persona.getEmail());
        bitacora.save(AdminLog.de(admin.email(), AdminLog.Action.persona_deshabilitada, persona.getOrgRut(),
                persona.getEmail()));
        return PersonaResponse.from(persona);
    }

    /** Le devuelve el acceso. No le manda nada: la persona pide su enlace cuando quiera entrar. */
    @Transactional
    public PersonaResponse habilitar(Long id, JwtPrincipal admin) {
        StaffUser persona = buscar(id);
        if (persona.habilitado()) {
            return PersonaResponse.from(persona);
        }
        persona.habilitar();
        personal.save(persona);
        bitacora.save(AdminLog.de(admin.email(), AdminLog.Action.persona_habilitada, persona.getOrgRut(),
                persona.getEmail()));
        return PersonaResponse.from(persona);
    }

    // ------------------------------------------------------------------
    //  Auxiliares
    // ------------------------------------------------------------------

    private StaffUser guardar(String direccion, String nombre, StaffUser.Role rol, String orgRut) {
        StaffUser persona = new StaffUser();
        persona.setEmail(direccion);
        persona.setFullName(nombre.strip());
        persona.setRole(rol);
        persona.setOrgRut(orgRut);
        try {
            return personal.saveAndFlush(persona);
        } catch (DataIntegrityViolationException repetida) {
            //  Dos altas del mismo correo a la vez: la base deja pasar una sola.
            throw new ApiException(HttpStatus.CONFLICT, "Ese correo ya tiene una cuenta");
        }
    }

    /**
     * El primer enlace de la persona. Dura mas que el que alguien pide por su
     * cuenta (horas, no minutos), porque no lo estaba esperando; sigue siendo de
     * un solo uso, y deja de servir si le quitan el acceso antes de abrirlo.
     */
    private void invitar(StaffUser persona, String aQue, String entrada) {
        String token = UUID.randomUUID().toString();
        MagicLink enlace = new MagicLink();
        enlace.setTokenHash(Hash.sha256(token));
        enlace.setEmail(persona.getEmail());
        enlace.setExpiresAt(Instant.now().plus(invitacionHoras, ChronoUnit.HOURS));
        enlaces.save(enlace);
        correo.enviarInvitacion(persona.getEmail(), persona.getFullName(), aQue,
                publicUrl + "/magic?token=" + token, invitacionHoras, publicUrl + entrada);
    }

    private StaffUser buscar(Long id) {
        return personal.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No hay ninguna persona con ese id"));
    }

    private static String rutValido(String crudo) {
        String rut = Rut.normalizar(crudo);
        if (!Rut.esValido(rut)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RUT no valido");
        }
        return rut;
    }

    private static String correoValido(String crudo) {
        return Correo.normalizar(crudo).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                "Ese correo no es valido: revisa que este completo, como nombre@empresa.cl"));
    }
}
