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
    private final DebtorRepository debtors;
    private final EmailChangeRepository changes;
    private final ContactUpdateRepository audit;
    private final ContactNoticeRepository notices;
    private final ContactoRateLimit limits;
    private final ContactoHuella hash;
    private final AuthClient auth;
    private final MisDatosService datos;
    public record Pendiente(String solicitudId, Instant expiraEn) {}
    public CorreoPersonalService(DebtorRepository debtors, EmailChangeRepository changes,
            ContactUpdateRepository audit, ContactNoticeRepository notices, ContactoRateLimit limits,
            ContactoHuella hash, AuthClient auth, MisDatosService datos) {
        this.debtors=debtors; this.changes=changes; this.audit=audit; this.notices=notices;
        this.limits=limits; this.hash=hash; this.auth=auth; this.datos=datos;
    }
    private Debtor bloquear(JwtPrincipal user) {
        ContactoPersonalService.exigirDeudor(user);
        return debtors.bloquearPorRut(user.rut()).orElseThrow(() ->
                new ApiException(HttpStatus.NOT_FOUND, "No hay datos a tu nombre"));
    }
    @Transactional(noRollbackFor = ApiException.class)
    public Pendiente solicitar(JwtPrincipal user, String email, String origin) {
        ContactoPersonalService.exigirDeudor(user);
        String normalized = Correo.normalizar(email).orElseThrow(() ->
                error(HttpStatus.BAD_REQUEST, "Escribe un correo valido")).toLowerCase(Locale.ROOT);
        String originKey = hash.calcular("EMAIL_CHANGE_ORIGIN", origin == null ? "unknown" : origin);
        // Same lock order across instances: origin bucket, debtor bucket, debtor.
        limits.tomar(originKey, 10);
        limits.tomar(hash.calcular("EMAIL_CHANGE_DEBTOR", user.rut()), 3);
        Debtor d = bloquear(user);
        if (normalized.equalsIgnoreCase(d.getEmail()))
            throw error(HttpStatus.BAD_REQUEST, "Ese ya es tu correo vigente");
        changes.reemplazar(d.getId());
        EmailChange e = new EmailChange();
        e.id=UUID.randomUUID().toString(); e.debtorId=d.getId(); e.rut=d.getRut(); e.newEmail=normalized;
        e.issuedAt=Instant.now(); e.expiresAt=e.issuedAt.plusSeconds(900); e.status="PENDING";
        StringBuilder code = new StringBuilder();
        for (int i=0;i<6;i++) code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        e.codeHash=hash.calcular("EMAIL_CHANGE_VERIFICATION", e.id + ":" + d.getRut() + ":" + e.newEmail + ":" + code);
        changes.saveAndFlush(e);
        try { auth.verificarCorreo(normalized, code.toString()); }
        catch (RuntimeException failed) {
            e.status="FAILED";
            throw error(HttpStatus.SERVICE_UNAVAILABLE,
                    "No se pudo enviar la verificacion al destino propuesto. Revisa la disponibilidad del correo.");
        }
        return new Pendiente(e.id, e.expiresAt);
    }
    @Transactional(noRollbackFor = ApiException.class)
    public MisDatosResponse confirmar(JwtPrincipal user, String id, String code) {
        Debtor d = bloquear(user);
        EmailChange e = changes.findByIdAndDebtorId(id, d.getId()).orElseThrow(() ->
                error(HttpStatus.BAD_REQUEST, "La solicitud no esta disponible"));
        if (!d.getRut().equals(e.rut) || !"PENDING".equals(e.status))
            throw error(HttpStatus.CONFLICT, "La solicitud fue sustituida, cancelada o ya utilizada");
        if (!Instant.now().isBefore(e.expiresAt))
            throw error(HttpStatus.GONE, "El codigo vencio. Solicita otro.");
        if (e.attempts >= 5) throw error(HttpStatus.TOO_MANY_REQUESTS, "Demasiados intentos. Solicita otro codigo.");
        String normalized = code == null ? "" : code.strip().toUpperCase(Locale.ROOT);
        String expected = hash.calcular("EMAIL_CHANGE_VERIFICATION", e.id + ":" + d.getRut() + ":" + e.newEmail + ":" + normalized);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII), e.codeHash.getBytes(StandardCharsets.US_ASCII))) {
            e.attempts++;
            throw error(e.attempts >= 5 ? HttpStatus.TOO_MANY_REQUESTS : HttpStatus.BAD_REQUEST,
                    e.attempts >= 5 ? "Demasiados intentos. Solicita otro codigo." : "El codigo es incorrecto");
        }
        Instant now = Instant.now();
        String old = d.getEmail();
        d.actualizarCorreo(e.newEmail, now);
        e.status="CONSUMED";
        audit.save(new ContactUpdate(d.getId(), "EMAIL", now));
        if (old != null && !old.isBlank()) {
            ContactNotice notice = new ContactNotice();
            notice.id=e.id; notice.oldEmail=old; notice.nextAttemptAt=now;
            notices.save(notice);
        }
        debtors.save(d);
        return datos.ver(user);
    }
    @Transactional
    public void cancelar(JwtPrincipal user, String id) {
        Debtor d = bloquear(user);
        changes.findByIdAndDebtorId(id, d.getId()).filter(e -> "PENDING".equals(e.status))
                .ifPresent(e -> e.status="CANCELLED");
    }
    private static ApiException error(HttpStatus status, String message) { return new ApiException(status, message); }
}
