package com.tbridge.debt.service;
import com.tbridge.debt.client.AuthClient;
import com.tbridge.debt.dto.response.MisDatosResponse;
import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.model.*;
import com.tbridge.debt.repository.*;
import com.tbridge.debt.security.JwtPrincipal;
import com.tbridge.debt.util.Correo;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Locale;
import java.util.UUID;

@Service
public class CorreoPersonalService {
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    private final DebtorRepository deudores;
    private final EmailChangeRepository solicitudesCorreo;
    private final ContactUpdateRepository bitacora;
    private final ContactNoticeRepository avisos;
    private final ContactoRateLimit limites;
    private final ContactoHuella huella;
    private final AuthClient acceso;
    private final MisDatosService datos;
    public record Pendiente(String solicitudId, Instant expiraEn) {}
    public CorreoPersonalService(DebtorRepository deudores, EmailChangeRepository solicitudesCorreo,
            ContactUpdateRepository bitacora, ContactNoticeRepository avisos, ContactoRateLimit limites,
            ContactoHuella huella, AuthClient acceso, MisDatosService datos) {
        this.deudores=deudores; this.solicitudesCorreo=solicitudesCorreo; this.bitacora=bitacora; this.avisos=avisos;
        this.limites=limites; this.huella=huella; this.acceso=acceso; this.datos=datos;
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
        StringBuilder codigo = new StringBuilder();
        for (int i=0;i<6;i++) codigo.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        e.codeHash=huella.calcular("EMAIL_CHANGE_VERIFICATION", e.id + ":" + d.getRut() + ":" + e.newEmail + ":" + codigo);
        solicitudesCorreo.saveAndFlush(e);
        try { acceso.verificarCorreo(correoNormalizado, codigo.toString()); }
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
            ContactNotice aviso = new ContactNotice();
            aviso.id=e.id; aviso.oldEmail=correoAnterior; aviso.nextAttemptAt=ahora;
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
    private static ApiException error(HttpStatus estado, String mensaje) { return new ApiException(estado, mensaje); }
}
