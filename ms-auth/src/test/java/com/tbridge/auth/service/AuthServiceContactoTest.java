package com.tbridge.auth.service;

import com.tbridge.auth.client.DeudasClient;
import com.tbridge.auth.exception.ApiException;
import com.tbridge.auth.model.Session;
import com.tbridge.auth.repository.AccessCodeRepository;
import com.tbridge.auth.repository.AccessLogRepository;
import com.tbridge.auth.repository.MagicLinkRepository;
import com.tbridge.auth.repository.StaffUserRepository;
import com.tbridge.auth.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AuthServiceContactoTest {
    private static final String RUT = "16482337-7";
    private final AccessCodeRepository codigos = mock(AccessCodeRepository.class);
    private final MagicLinkRepository enlaces = mock(MagicLinkRepository.class);
    private final StaffUserRepository personal = mock(StaffUserRepository.class);
    private final AccessLogRepository bitacora = mock(AccessLogRepository.class);
    private final JwtService jwt = mock(JwtService.class);
    private final SessionService sesiones = mock(SessionService.class);
    private final MailService correo = mock(MailService.class);
    private final DeudasClient deudas = mock(DeudasClient.class);
    private final AuthService servicio = new AuthService(codigos, enlaces, personal, bitacora, jwt, sesiones,
            correo, deudas, "http://localhost", 24, 15);

    @Test void cambioRecientePosteriorALaSesionRevocaYAbreFamiliaNueva() {
        Instant ahora = Instant.now();
        when(sesiones.vigente("llave-vigente")).thenReturn(new SessionService.Vigente(Session.Role.DEBTOR, RUT, "familia-anterior"));
        when(deudas.fechaCambioCorreo(RUT)).thenReturn(ahora.minusSeconds(30));
        when(sesiones.inicioDeFamilia("familia-anterior")).thenReturn(ahora.minusSeconds(60));
        when(sesiones.abrir(eq(Session.Role.DEBTOR), eq(RUT), isNull(), any())).thenReturn(
                new SessionService.Llave("llave-nueva", ahora.plusSeconds(604800)));

        AuthService.Sesion resultado = servicio.sesionTrasCambioDeCorreo("llave-vigente", "192.0.2.2");

        assertNotNull(resultado);
        verify(sesiones).revocarDeDeudor(RUT);
        verify(sesiones).abrir(eq(Session.Role.DEBTOR), eq(RUT), isNull(), any());
    }

    @Test void cambioAusenteAntiguoOAnteriorAlInicioResponde409() {
        Instant ahora = Instant.now();
        when(sesiones.vigente("llave")).thenReturn(new SessionService.Vigente(Session.Role.DEBTOR, RUT, "familia"));
        when(sesiones.inicioDeFamilia("familia")).thenReturn(ahora.minusSeconds(60));

        when(deudas.fechaCambioCorreo(RUT)).thenReturn(null);
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> servicio.sesionTrasCambioDeCorreo("llave", null)).getStatus());
        when(deudas.fechaCambioCorreo(RUT)).thenReturn(ahora.minusSeconds(601));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> servicio.sesionTrasCambioDeCorreo("llave", null)).getStatus());
        when(deudas.fechaCambioCorreo(RUT)).thenReturn(ahora.minusSeconds(30));
        when(sesiones.inicioDeFamilia("familia")).thenReturn(ahora.minusSeconds(10));
        assertEquals(HttpStatus.CONFLICT, assertThrows(ApiException.class, () -> servicio.sesionTrasCambioDeCorreo("llave", null)).getStatus());
        verify(sesiones, never()).revocarDeDeudor(anyString());
    }

    @Test void usuarioQueNoEsDeudorResponde403() {
        when(sesiones.vigente("llave")).thenReturn(new SessionService.Vigente(Session.Role.CREDITOR, null, "familia"));
        assertEquals(HttpStatus.FORBIDDEN, assertThrows(ApiException.class, () -> servicio.sesionTrasCambioDeCorreo("llave", null)).getStatus());
        verifyNoInteractions(deudas);
    }
}
