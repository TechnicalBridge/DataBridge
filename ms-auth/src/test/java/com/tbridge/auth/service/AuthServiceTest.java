package com.tbridge.auth.service;

import com.tbridge.auth.client.DeudasClient;
import com.tbridge.auth.model.AccessCode;
import com.tbridge.auth.model.MagicLink;
import com.tbridge.auth.model.StaffUser;
import com.tbridge.auth.model.AccessLog;
import com.tbridge.auth.repository.AccessCodeRepository;
import com.tbridge.auth.repository.AccessLogRepository;
import com.tbridge.auth.repository.MagicLinkRepository;
import com.tbridge.auth.repository.StaffUserRepository;
import com.tbridge.auth.security.JwtService;
import com.tbridge.auth.exception.ApiException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.tbridge.auth.model.Session;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * El acceso del deudor.
 *
 * Lo que se prueba aca no es que "funcione el login": es que las reglas que
 * sostienen la tesis del caso se cumplan. Un codigo de un solo uso, que expira,
 * que se puede agotar a intentos, y que en la base no queda escrito.
 */
class AuthServiceTest {

    private final List<AccessCode> guardados = new ArrayList<>();
    private final List<AccessLog> bitacora = new ArrayList<>();
    private final SesionesEnMemoria sesiones = new SesionesEnMemoria();
    private AuthService auth;
    private MagicLinkRepository enlaces;
    private StaffUserRepository personal;
    private MailService correo;
    private DeudasClient deudas;

    @BeforeEach
    void preparar() {
        AccessCodeRepository codigos = mock(AccessCodeRepository.class);
        enlaces = mock(MagicLinkRepository.class);
        personal = mock(StaffUserRepository.class);
        AccessLogRepository registro = mock(AccessLogRepository.class);
        correo = mock(MailService.class);
        deudas = mock(DeudasClient.class);
        when(deudas.correoDelDeudor(any())).thenReturn(Optional.empty());
        JwtService jwt = new JwtService("unit-test-secret-key-32-chars!!!", 15);
        SessionService sesiones = new SessionService(this.sesiones.repositorio,
                java.time.Duration.ofDays(7), java.time.Clock.systemUTC());

        when(codigos.save(any())).thenAnswer(llamada -> {
            AccessCode codigo = llamada.getArgument(0);
            guardados.remove(codigo);
            guardados.add(codigo);
            return codigo;
        });
        when(codigos.findFirstByDebtorRutAndConsumedAtIsNullOrderByIssuedAtDesc(any()))
                .thenAnswer(llamada -> guardados.stream()
                        .filter(c -> c.getDebtorRut().equals(llamada.getArgument(0)))
                        .filter(c -> c.getConsumedAt() == null)
                        .reduce((primero, ultimo) -> ultimo));
        when(registro.save(any())).thenAnswer(llamada -> {
            bitacora.add(llamada.getArgument(0));
            return llamada.getArgument(0);
        });
        when(registro.findByIpHashAndOccurredAtAfter(any(), any()))
                .thenAnswer(llamada -> bitacora.stream()
                        .filter(r -> llamada.getArgument(0).equals(r.getIpHash()))
                        .toList());
        when(personal.findByEmailIgnoreCase(any())).thenReturn(Optional.empty());
        when(enlaces.findByTokenHash(any())).thenReturn(Optional.empty());

        auth = new AuthService(codigos, enlaces, personal, registro, jwt, sesiones, correo, deudas,
                "http://localhost:5173", 24, 15);
    }

    private String emitir(String rut) {
        return auth.emitirCodigo(rut, List.of("whatsapp", "correo"), "prueba").codigo();
    }

    @Test
    void el_codigo_abre_la_sesion_y_lleva_el_rut() {
        String codigo = emitir("16482337-7");
        AuthService.Sesion sesion = auth.entrarConCodigo("16482337-7", codigo, "1.2.3.4");

        assertNotNull(sesion.token());
        assertEquals("16482337-7", sesion.user().rut());
    }

    @Test
    void entrar_abre_una_sesion_revocable_y_la_llave_no_queda_escrita() {
        String codigo = emitir("16482337-7");
        AuthService.Sesion sesion = auth.entrarConCodigo("16482337-7", codigo, "1.2.3.4");

        assertEquals(1, sesiones.filas.size());
        String guardada = sesiones.filas.get(0).getRefreshHash();
        assertNotEquals(sesion.llave().valor(), guardada);
        assertEquals(SessionService.huella(sesion.llave().valor()), guardada);
    }

    @Test
    void el_codigo_no_queda_escrito_en_la_base() {
        String codigo = emitir("16482337-7");
        AccessCode guardado = guardados.getFirst();

        assertNotEquals(codigo, guardado.getCodeHash());
        assertFalse(guardado.getCodeHash().contains(codigo));
        assertEquals(64, guardado.getCodeHash().length(), "es un SHA-256 en hexadecimal");
    }

    @Test
    void el_codigo_sirve_una_sola_vez() {
        String codigo = emitir("16482337-7");
        auth.entrarConCodigo("16482337-7", codigo, "1.2.3.4");

        ApiException segunda = assertThrows(ApiException.class,
                () -> auth.entrarConCodigo("16482337-7", codigo, "1.2.3.4"));
        assertEquals(HttpStatus.UNAUTHORIZED, segunda.getStatus());
    }

    @Test
    void el_codigo_de_otro_rut_no_sirve() {
        emitir("16482337-7");
        String deValentina = emitir("18905214-6");

        assertThrows(ApiException.class,
                () -> auth.entrarConCodigo("16482337-7", deValentina, "1.2.3.4"));
    }

    @Test
    void el_codigo_se_agota_a_intentos() {
        String codigo = emitir("16482337-7");

        for (int i = 0; i < 5; i++) {
            assertThrows(ApiException.class,
                    () -> auth.entrarConCodigo("16482337-7", "ZZZZZZ", "9.9.9." + System.nanoTime()));
        }
        //  Agotado: ni siquiera el codigo correcto entra.
        ApiException agotado = assertThrows(ApiException.class,
                () -> auth.entrarConCodigo("16482337-7", codigo, "1.2.3.4"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, agotado.getStatus());
    }

    @Test
    void el_codigo_expira() {
        String codigo = emitir("16482337-7");
        guardados.getFirst().setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));

        ApiException vencido = assertThrows(ApiException.class,
                () -> auth.entrarConCodigo("16482337-7", codigo, "1.2.3.4"));
        assertTrue(vencido.getMessage().contains("expiro"));
    }

    @Test
    void demasiados_intentos_desde_el_mismo_origen_se_frenan() {
        emitir("16482337-7");
        for (int i = 0; i < 10; i++) {
            try {
                auth.entrarConCodigo("16482337-7", "ZZZZZZ", "5.5.5.5");
            } catch (ApiException ignorada) {
                // cada intento fallido queda anotado
            }
        }
        ApiException frenado = assertThrows(ApiException.class,
                () -> auth.entrarConCodigo("16482337-7", "ZZZZZZ", "5.5.5.5"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, frenado.getStatus());
    }

    @Test
    void un_rut_con_digito_verificador_falso_no_entra() {
        ApiException malo = assertThrows(ApiException.class,
                () -> auth.entrarConCodigo("16482337-8", "ABC123", "1.2.3.4"));
        assertEquals(HttpStatus.BAD_REQUEST, malo.getStatus());
    }

    @Test
    void el_codigo_no_trae_caracteres_ambiguos() {
        for (int i = 0; i < 200; i++) {
            String codigo = emitir("16482337-7");
            assertEquals(6, codigo.length());
            for (char caracter : "01OIL".toCharArray()) {
                assertFalse(codigo.indexOf(caracter) >= 0,
                        "el codigo se dicta por telefono: " + codigo + " trae " + caracter);
            }
        }
    }

    @Test
    void el_mensaje_no_distingue_entre_rut_sin_deuda_y_codigo_equivocado() {
        String codigo = emitir("16482337-7");
        String sinCodigo = assertThrows(ApiException.class,
                () -> auth.entrarConCodigo("18905214-6", codigo, "1.2.3.4")).getMessage();
        String equivocado = assertThrows(ApiException.class,
                () -> auth.entrarConCodigo("16482337-7", "ZZZZZZ", "1.2.3.4")).getMessage();

        //  Si fueran distintos, se podria averiguar que RUT tienen deuda.
        assertEquals(sinCodigo, equivocado);
    }

    // ------------------------------------------------------------------
    //  El enlace de acceso (#59): solo al correo registrado
    // ------------------------------------------------------------------

    private StaffUser camila(boolean habilitada) {
        StaffUser staff = new StaffUser();
        staff.setEmail("camila.reyes@apofyx.cl");
        staff.setFullName("Camila Reyes");
        staff.setOrgRut("77305118-6");
        staff.setRole(StaffUser.Role.usuario);
        if (!habilitada) {
            org.springframework.test.util.ReflectionTestUtils.setField(staff, "disabledAt", Instant.now());
        }
        return staff;
    }

    private AccessLog.Outcome ultimoPedido() {
        return bitacora.getLast().getOutcome();
    }

    @Test
    void el_enlace_de_un_deudor_va_solo_al_correo_que_registro_el_acreedor() {
        when(deudas.correoDelDeudor("16482337-7")).thenReturn(Optional.of("felipe.rojas@correo.cl"));

        var respuesta = auth.pedirEnlace("16.482.337-7", "atacante@correo.cl", "10.0.0.1");

        verify(correo).enviarEnlace(eq("felipe.rojas@correo.cl"), anyString(), anyLong());
        verify(correo, never()).enviarEnlace(eq("atacante@correo.cl"), anyString(), anyLong());
        assertEquals(AuthService.RESPUESTA_ENLACE, respuesta.mensaje());
        assertEquals(AccessLog.Outcome.sent, ultimoPedido());
    }

    @Test
    void un_rut_sin_correo_registrado_no_recibe_nada_y_la_respuesta_es_la_misma() {
        var respuesta = auth.pedirEnlace("16482337-7", "atacante@correo.cl", "10.0.0.1");

        verify(correo, never()).enviarEnlace(anyString(), anyString(), anyLong());
        verify(enlaces, never()).save(any());
        assertEquals(AuthService.RESPUESTA_ENLACE, respuesta.mensaje());
        assertEquals(AccessLog.Outcome.refused, ultimoPedido());
    }

    @Test
    void el_personal_habilitado_recibe_su_enlace_y_el_que_no_esta_habilitado_no() {
        when(personal.findByEmailIgnoreCase("camila.reyes@apofyx.cl")).thenReturn(Optional.of(camila(true)));
        auth.pedirEnlace(null, " camila.reyes@apofyx.cl ", "10.0.0.1");
        verify(correo).enviarEnlace(eq("camila.reyes@apofyx.cl"), anyString(), anyLong());

        when(personal.findByEmailIgnoreCase("camila.reyes@apofyx.cl")).thenReturn(Optional.of(camila(false)));
        var respuesta = auth.pedirEnlace(null, "camila.reyes@apofyx.cl", "10.0.0.1");
        verify(correo).enviarEnlace(anyString(), anyString(), anyLong());
        assertEquals(AuthService.RESPUESTA_ENLACE, respuesta.mensaje());
        assertEquals(AccessLog.Outcome.refused, ultimoPedido());
    }

    @Test
    void un_correo_que_no_es_de_nadie_no_recibe_enlace() {
        var respuesta = auth.pedirEnlace(null, "cualquiera@correo.cl", "10.0.0.1");

        verify(correo, never()).enviarEnlace(anyString(), anyString(), anyLong());
        assertEquals(AuthService.RESPUESTA_ENLACE, respuesta.mensaje());
    }

    @Test
    void sin_rut_y_sin_correo_es_un_pedido_malo() {
        ApiException error = assertThrows(ApiException.class, () -> auth.pedirEnlace(null, null, "10.0.0.1"));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatus());
    }

    @Test
    void un_enlace_pedido_con_rut_abre_la_sesion_de_ese_deudor_aunque_su_correo_sea_de_personal() {
        MagicLink enlace = new MagicLink();
        enlace.setDebtorRut("16482337-7");
        enlace.setEmail("camila.reyes@apofyx.cl");
        enlace.setExpiresAt(Instant.now().plus(10, ChronoUnit.MINUTES));
        when(enlaces.findByTokenHash(any())).thenReturn(Optional.of(enlace));
        when(personal.findByEmailIgnoreCase("camila.reyes@apofyx.cl")).thenReturn(Optional.of(camila(true)));

        var sesion = auth.entrarConEnlace("token", "10.0.0.1");

        assertEquals("16482337-7", sesion.user().rut());
        assertEquals("16482337-7", sesion.user().id(), "es la sesion del deudor, no la de la empresa");
    }

    // ------------------------------------------------------------------
    //  Los roles (#137): el admin de DataBridge y el usuario de una empresa
    // ------------------------------------------------------------------

    private StaffUser admin() {
        StaffUser staff = new StaffUser();
        staff.setEmail("admin@databridge.cl");
        staff.setFullName("Administrador de DataBridge");
        staff.setRole(StaffUser.Role.admin);
        return staff;
    }

    /** Deja listo un enlace vigente de esa persona, que es como entra el personal. */
    private void conEnlaceDe(StaffUser staff) {
        MagicLink enlace = new MagicLink();
        enlace.setEmail(staff.getEmail());
        enlace.setExpiresAt(Instant.now().plus(10, ChronoUnit.MINUTES));
        when(enlaces.findByTokenHash(any())).thenReturn(Optional.of(enlace));
        when(personal.findByEmailIgnoreCase(staff.getEmail())).thenReturn(Optional.of(staff));
    }

    private static io.jsonwebtoken.Claims loQueDiceElJwt(AuthService.Sesion sesion) {
        return new JwtService("unit-test-secret-key-32-chars!!!", 15).parse(sesion.token());
    }

    @Test
    void el_admin_de_databridge_entra_con_su_propio_rol_y_sin_empresa() {
        conEnlaceDe(admin());

        AuthService.Sesion sesion = auth.entrarConEnlace("token", "10.0.0.1");

        assertEquals("ADMIN", sesion.user().role());
        assertNull(sesion.user().rut(), "el admin no es de ninguna empresa");
        assertEquals("ADMIN", loQueDiceElJwt(sesion).get("role", String.class));
        assertNull(loQueDiceElJwt(sesion).get("rut", String.class));
        assertEquals(Session.Role.ADMIN, sesiones.filas.getLast().getRole());
    }

    @Test
    void el_usuario_de_una_empresa_sigue_entrando_como_empresa_con_el_rut_de_la_suya() {
        conEnlaceDe(camila(true));

        AuthService.Sesion sesion = auth.entrarConEnlace("token", "10.0.0.1");

        assertEquals("CREDITOR", sesion.user().role());
        assertEquals("77305118-6", sesion.user().rut());
        assertEquals("CREDITOR", loQueDiceElJwt(sesion).get("role", String.class));
        assertEquals(Session.Role.CREDITOR, sesiones.filas.getLast().getRole());
    }

    @Test
    void la_llave_del_admin_renueva_como_admin() {
        conEnlaceDe(admin());
        AuthService.Sesion sesion = auth.entrarConEnlace("token", "10.0.0.1");

        AuthService.Sesion renovada = auth.renovar(sesion.llave().valor(), "10.0.0.1");

        assertEquals("ADMIN", renovada.user().role());
        assertEquals("ADMIN", loQueDiceElJwt(renovada).get("role", String.class));
    }

    @Test
    void una_llave_de_empresa_no_renueva_como_admin_y_la_sesion_se_cierra() {
        conEnlaceDe(camila(true));
        AuthService.Sesion sesion = auth.entrarConEnlace("token", "10.0.0.1");
        //  Aunque la misma persona apareciera despues como admin, su llave sigue siendo de empresa.
        StaffUser ahoraAdmin = admin();
        ahoraAdmin.setEmail("camila.reyes@apofyx.cl");
        when(personal.findByEmailIgnoreCase("camila.reyes@apofyx.cl")).thenReturn(Optional.of(ahoraAdmin));

        ApiException error = assertThrows(ApiException.class, () -> auth.renovar(sesion.llave().valor(), "10.0.0.1"));

        assertEquals(HttpStatus.UNAUTHORIZED, error.getStatus());
        assertTrue(sesiones.filas.stream().allMatch(Session::revocada), "toda la familia queda revocada");
    }
}
