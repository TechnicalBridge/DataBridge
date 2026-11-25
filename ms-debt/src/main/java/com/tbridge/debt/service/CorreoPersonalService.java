package com.tbridge.debt.service;
import com.tbridge.debt.client.AuthClient;
import com.tbridge.debt.dto.response.MisDatosResponse;
import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.model.*;
import com.tbridge.debt.repository.*;
import com.tbridge.debt.security.JwtPrincipal;
import com.tbridge.debt.util.Correo;
import com.tbridge.debt.util.Rut;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class CorreoPersonalService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    /** Cuanto vale el codigo para deshacer un cambio de correo (#471). */
    static final Duration PARA_DESHACER = Duration.ofDays(7);
    /** El mismo mensaje haya o no un deudor con ese RUT: no se confirma quien existe. */
    static final String CODIGO_INVALIDO = "El código no es válido o ya venció";
    private final DebtorRepository deudores;
    private final EmailChangeRepository solicitudesCorreo;
    private final ContactUpdateRepository bitacora;
    private final ContactNoticeRepository avisos;
    private final ContactReversionRepository reversiones;
    private final ContactoRateLimit limites;
    private final ContactoHuella huella;
    private final AuthClient acceso;
    private final MisDatosService datos;
    public record Pendiente(String solicitudId, Instant expiraEn) {}
    public CorreoPersonalService(DebtorRepository deudores, EmailChangeRepository solicitudesCorreo,
            ContactUpdateRepository bitacora, ContactNoticeRepository avisos, ContactReversionRepository reversiones,
            ContactoRateLimit limites, ContactoHuella huella, AuthClient acceso, MisDatosService datos) {
        this.deudores=deudores; this.solicitudesCorreo=solicitudesCorreo; this.bitacora=bitacora; this.avisos=avisos;
        this.reversiones=reversiones; this.limites=limites; this.huella=huella; this.acceso=acceso; this.datos=datos;
    }
    private Debtor bloquear(JwtPrincipal deudor) {
        ContactoPersonalService.exigirDeudor(deudor);
        return deudores.bloquearPorRut(deudor.rut()).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "No hay datos a tu nombre"));
    }
    @Transactional(noRollbackFor = ApiException.class)
    public Pendiente solicitar(JwtPrincipal deudor, String correo, String origen) {
        ContactoPersonalService.exigirDeudor(deudor);
        String correoNormalizado = Correo.normalizar(correo).orElseThrow(() ->
                error(HttpStatus.BAD_REQUEST, "Escribe un correo válido")).toLowerCase(Locale.ROOT);
        String huellaOrigen = huella.calcular("EMAIL_CHANGE_ORIGIN", origen == null ? "unknown" : origen);
        // Mismo orden de bloqueos en todas las instancias: origen, deudor y ficha.
        limites.tomar(huellaOrigen, 10);
        limites.tomar(huella.calcular("EMAIL_CHANGE_DEBTOR", deudor.rut()), 3);
        Debtor d = bloquear(deudor);
        if (correoNormalizado.equalsIgnoreCase(d.getEmail()))
            throw error(HttpStatus.BAD_REQUEST, "Ese ya es tu correo vigente");
        solicitudesCorreo.reemplazar(d.getId());
        EmailChange e = new EmailChange();
        e.id=UUID.randomUUID().toString(); e.debtorId=d.getId(); e.rut=d.getRut(); e.newEmail=correoNormalizado;
        e.issuedAt=Instant.now(); e.expiresAt=e.issuedAt.plusSeconds(900); e.status="PENDING";
        String codigo = codigo(6);
        e.codeHash=huella.calcular("EMAIL_CHANGE_VERIFICATION", e.id + ":" + d.getRut() + ":" + e.newEmail + ":" + codigo);
        solicitudesCorreo.saveAndFlush(e);
        try { acceso.verificarCorreo(correoNormalizado, codigo); }
        catch (RuntimeException fallo) {
            e.status="FAILED";
            throw error(HttpStatus.SERVICE_UNAVAILABLE,
                    "No se pudo enviar la verificación al destino propuesto. Revisa la disponibilidad del correo.");
        }
        return new Pendiente(e.id, e.expiresAt);
    }
    @Transactional(noRollbackFor = ApiException.class)
    public MisDatosResponse confirmar(JwtPrincipal deudor, String solicitudId, String codigo) {
        Debtor d = bloquear(deudor);
        EmailChange e = solicitudesCorreo.findByIdAndDebtorId(solicitudId, d.getId()).orElseThrow(() ->
                error(HttpStatus.BAD_REQUEST, "La solicitud no está disponible"));
        if (!d.getRut().equals(e.rut) || !"PENDING".equals(e.status))
            throw error(HttpStatus.CONFLICT, "La solicitud fue sustituida, cancelada o ya utilizada");
        if (!Instant.now().isBefore(e.expiresAt))
            throw error(HttpStatus.GONE, "El código venció. Solicita otro.");
        if (e.attempts >= 5) throw error(HttpStatus.TOO_MANY_REQUESTS, "Demasiados intentos. Solicita otro código.");
        String codigoNormalizado = codigo == null ? "" : codigo.strip().toUpperCase(Locale.ROOT);
        String huellaEsperada = huella.calcular("EMAIL_CHANGE_VERIFICATION", e.id + ":" + d.getRut() + ":" + e.newEmail + ":" + codigoNormalizado);
        if (!MessageDigest.isEqual(huellaEsperada.getBytes(StandardCharsets.US_ASCII), e.codeHash.getBytes(StandardCharsets.US_ASCII))) {
            e.attempts++;
            throw error(e.attempts >= 5 ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_REQUEST,
                    e.attempts >= 5 ? "Demasiados intentos. Solicita otro código." : "El código es incorrecto");
        }
        Instant ahora = Instant.now();
        String correoAnterior = d.getEmail();
        d.actualizarCorreo(e.newEmail, ahora);
        e.status="CONSUMED";
        bitacora.save(new ContactUpdate(d.getId(), "EMAIL", ahora));
        if (correoAnterior != null && !correoAnterior.isBlank()) {
            // Si no fue el deudor, el aviso al correo anterior trae como deshacerlo: un codigo de 7 dias (#471).
            String codigoParaDeshacer = codigo(8);
            ContactReversion reversa = new ContactReversion();
            reversa.id=e.id; reversa.debtorId=d.getId(); reversa.rut=d.getRut(); reversa.previousEmail=correoAnterior;
            reversa.codeHash=huellaParaDeshacer(reversa.id, d.getRut(), codigoParaDeshacer);
            reversa.expiresAt=ahora.plus(PARA_DESHACER); reversa.status="PENDING"; reversa.createdAt=ahora;
            reversiones.save(reversa);
            ContactNotice aviso = new ContactNotice();
            aviso.id=e.id; aviso.oldEmail=correoAnterior; aviso.revertCode=codigoParaDeshacer; aviso.nextAttemptAt=ahora;
            avisos.save(aviso);
        }
        deudores.save(d);
        return datos.ver(deudor);
    }
    @Transactional
    public void cancelar(JwtPrincipal deudor, String solicitudId) {
        Debtor d = bloquear(deudor);
        solicitudesCorreo.findByIdAndDebtorId(solicitudId, d.getId()).filter(e -> "PENDING".equals(e.status))
                .ifPresent(e -> e.status="CANCELLED");
    }
    /**
     * Deshace un cambio de correo que el deudor no pidio (#471), con el codigo que le llego al correo anterior.
     * Va sin sesion: quien la perdio no puede entrar. Devuelve el correo anterior, cancela las verificaciones
     * pendientes y los otros codigos, anota el cambio y cierra todas las sesiones del deudor.
     *
     * <p>Un codigo errado responde lo mismo exista o no el RUT. Cada fallo suma un intento a los codigos vivos
     * del deudor, y con cinco dejan de servir; ademas hay limites por origen y por RUT cada 15 minutos.
     * Si ms-auth no responde, las sesiones viejas igual se rechazan al renovarse: el correo cambio ahora.
     */
    @Transactional(noRollbackFor = ApiException.class)
    public void deshacer(String rut, String codigoRecibido, String origen) {
        String rutLimpio = Rut.normalizar(rut);
        limites.tomar(huella.calcular("EMAIL_REVERT_ORIGIN", origen == null ? "unknown" : origen), 10);
        limites.tomar(huella.calcular("EMAIL_REVERT_RUT", rutLimpio), 5);
        Debtor d = deudores.bloquearPorRut(rutLimpio).orElseThrow(() -> error(HttpStatus.BAD_REQUEST, CODIGO_INVALIDO));
        String codigoNormalizado = codigoRecibido == null ? "" : codigoRecibido.strip().toUpperCase(Locale.ROOT);
        Instant ahora = Instant.now();
        List<ContactReversion> vivas = reversiones.findByRutAndStatus(d.getRut(), "PENDING").stream()
                .filter(r -> ahora.isBefore(r.expiresAt) && r.attempts < 5).toList();
        ContactReversion usada = vivas.stream()
                .filter(r -> MessageDigest.isEqual(huellaParaDeshacer(r.id, d.getRut(), codigoNormalizado).getBytes(StandardCharsets.US_ASCII),
                        r.codeHash.getBytes(StandardCharsets.US_ASCII)))
                .findFirst().orElse(null);
        if (usada == null) {
            vivas.forEach(r -> r.attempts++);
            throw error(HttpStatus.BAD_REQUEST, CODIGO_INVALIDO);
        }
        d.actualizarCorreo(usada.previousEmail, ahora);
        usada.status="USED"; usada.usedAt=ahora; usada.previousEmail=null;
        reversiones.cancelarOtras(d.getId(), usada.id);
        solicitudesCorreo.reemplazar(d.getId());
        bitacora.save(new ContactUpdate(d.getId(), "EMAIL", "CONTACT_REVERTED", ahora));
        deudores.save(d);
        try { acceso.revocarSesionesDeDeudor(d.getRut()); }
        catch (RuntimeException fallo) {
            // Sin ms-auth, la renovacion de cada sesion anterior se rechaza igual: el correo cambio ahora.
        }
    }

    private String huellaParaDeshacer(String id, String rut, String codigo) {
        return huella.calcular("EMAIL_REVERT", id + ":" + rut + ":" + codigo);
    }

    private static String codigo(int largo) {
        StringBuilder codigo = new StringBuilder();
        for (int i=0;i<largo;i++) codigo.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        return codigo.toString();
    }

    private static ApiException error(HttpStatus estado, String mensaje) { return new ApiException(estado, mensaje); }
}
