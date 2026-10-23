package com.tbridge.auth.service;

import com.tbridge.auth.client.DeudasClient;
import com.tbridge.auth.exception.ApiException;
import com.tbridge.auth.model.AccessLog;
import com.tbridge.auth.model.AccessCode;
import com.tbridge.auth.model.MagicLink;
import com.tbridge.auth.model.StaffUser;
import com.tbridge.auth.repository.*;
import com.tbridge.auth.security.JwtService;
import com.tbridge.auth.util.Hash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AccesoSolicitadoTest {
    private static final String RUT = "16482337-7";
    private final MagicLinkRepository enlaces = mock(MagicLinkRepository.class);
    private final AccessCodeRepository codigos = mock(AccessCodeRepository.class);
    private final StaffUserRepository personal = mock(StaffUserRepository.class);
    private final AccessLogRepository bitacora = mock(AccessLogRepository.class);
    private final DeudasClient deudas = mock(DeudasClient.class);
    private final MailService correo = mock(MailService.class);
    private final SesionesEnMemoria sesiones = new SesionesEnMemoria();
    private final List<MagicLink> pedidos = new ArrayList<>();
    private final List<Mensaje> mensajes = new ArrayList<>();
    private AuthService auth;

    record Mensaje(String para, String codigo, String url, Long minutos) {}

    @BeforeEach
    void preparar() {
        auth = new AuthService(codigos, enlaces, personal, bitacora,
                new JwtService("unit-test-secret-key-32-chars!!!", 15),
                new SessionService(sesiones.repositorio, java.time.Duration.ofDays(7), java.time.Clock.systemUTC()),
                correo, deudas, "http://localhost:8080", 24, 15);
        when(deudas.correoDelDeudor(RUT)).thenReturn(Optional.of("deudor@example.com"));
        when(deudas.empresa(any())).thenAnswer(i -> Optional.of(new DeudasClient.Empresa(i.getArgument(0), "Empresa", true)));
        when(enlaces.save(any())).thenAnswer(invocacion -> {
            MagicLink pedido = invocacion.getArgument(0);
            if (!pedidos.contains(pedido)) pedidos.add(pedido);
            return pedido;
        });
        when(enlaces.anularPedidosDeRut(any(), any())).thenAnswer(i -> {
            pedidos.stream().filter(p -> Objects.equals(p.getDebtorRut(), i.getArgument(0)))
                    .forEach(p -> p.setConsumedAt(i.getArgument(1)));
            return pedidos.size();
        });
        when(enlaces.anularPedidosDeCorreo(any(), any())).thenAnswer(i -> {
            pedidos.stream().filter(p -> p.getDebtorRut() == null && p.getEmail().equals(i.getArgument(0)))
                    .forEach(p -> p.setConsumedAt(i.getArgument(1)));
            return pedidos.size();
        });
        when(enlaces.findFirstByDebtorRutAndCodeHashIsNotNullOrderByIssuedAtDescIdDesc(any()))
                .thenAnswer(i -> pedidos.reversed().stream().filter(p -> Objects.equals(p.getDebtorRut(), i.getArgument(0)))
                        .findFirst());
        when(enlaces.findFirstByEmailIgnoreCaseAndDebtorRutIsNullAndCodeHashIsNotNullOrderByIssuedAtDescIdDesc(any()))
                .thenAnswer(i -> pedidos.reversed().stream().filter(p -> p.getDebtorRut() == null
                        && p.getEmail().equalsIgnoreCase(i.getArgument(0))).findFirst());
        when(enlaces.findByTokenHash(any())).thenAnswer(i -> {
            if (mensajes.isEmpty() || ultimo().url() == null) return Optional.empty();
            String token = ultimo().url().split("token=")[1];
            return Hash.sha256(token).equals(i.getArgument(0)) ? Optional.of(pedidos.getLast()) : Optional.empty();
        });
        doAnswer(i -> {
            mensajes.add(new Mensaje(i.getArgument(0), i.getArgument(1), i.getArgument(2), i.getArgument(3)));
            return null;
        }).when(correo).enviarAcceso(anyString(), anyString(), nullable(String.class), anyLong());
    }

    private Mensaje ultimo() { return mensajes.getLast(); }
    private String token() { return ultimo().url().split("token=")[1]; }

    @Test
    void el_envio_inicial_contiene_solo_codigo_y_no_devuelve_credenciales_en_la_respuesta() {
        var respuesta = auth.pedirCodigo(RUT, null, "127.0.0.1");
        assertEquals(1, mensajes.size());
        assertNull(ultimo().url());
        assertFalse(pedidos.getLast().isLinkEnabled());
        assertEquals(6, ultimo().codigo().length());
        assertNotEquals(ultimo().codigo(), pedidos.getLast().getCodeHash());
        assertFalse(respuesta.mensaje().contains(ultimo().codigo()));
        assertEquals(15, respuesta.expiraEnMinutos());
        assertEquals("DEBTOR", auth.entrarConCodigo(RUT, ultimo().codigo(), "127.0.0.1").user().role());
    }

    @Test
    void el_respaldo_es_un_mensaje_con_codigo_y_enlace_y_el_codigo_invalida_ambos() {
        auth.pedirEnlace(RUT, null, "127.0.0.1");
        assertEquals(1, mensajes.size());
        assertNotNull(ultimo().url());
        String token = token();
        auth.entrarConCodigo(RUT, ultimo().codigo(), "127.0.0.1");
        assertThrows(ApiException.class, () -> auth.entrarConEnlace(token, "127.0.0.1"));
        assertThrows(ApiException.class, () -> auth.entrarConCodigo(RUT, ultimo().codigo(), "127.0.0.1"));
        assertEquals(1, sesiones.filas.size());
    }

    @Test
    void entrar_con_el_enlace_invalida_el_codigo_del_mismo_correo() {
        auth.pedirEnlace(RUT, null, "127.0.0.1");
        auth.entrarConEnlace(token(), "127.0.0.1");
        assertThrows(ApiException.class, () -> auth.entrarConCodigo(RUT, ultimo().codigo(), "127.0.0.1"));
        assertEquals(1, sesiones.filas.size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"admin", "usuario"})
    void el_personal_entra_por_codigo_con_el_rol_que_tiene_en_la_base(String rol) {
        StaffUser staff = new StaffUser();
        staff.setEmail("persona@example.com");
        staff.setFullName("Persona de prueba");
        staff.setRole(StaffUser.Role.valueOf(rol));
        if (!staff.esAdmin()) staff.setOrgRut("76000000-0");
        when(personal.findByEmailIgnoreCase(staff.getEmail())).thenReturn(Optional.of(staff));
        auth.pedirCodigo(null, " PERSONA@example.com ", "127.0.0.1");
        var sesion = auth.entrarConCodigoDePersonal(staff.getEmail(), ultimo().codigo(), "127.0.0.1");
        assertEquals(staff.esAdmin() ? "ADMIN" : "CREDITOR", sesion.user().role());
        assertEquals(staff.getOrgRut(), sesion.user().rut());
        assertThrows(ApiException.class, () -> auth.entrarConCodigoDePersonal(staff.getEmail(), ultimo().codigo(), "127.0.0.1"));
    }

    @ParameterizedTest
    @CsvSource({"admin,true", "admin,false", "usuario,true", "usuario,false"})
    void el_respaldo_del_personal_comparte_un_solo_uso(String rol, boolean porEnlace) {
        StaffUser staff = new StaffUser();
        staff.setEmail("persona@example.com");
        staff.setRole(StaffUser.Role.valueOf(rol));
        if (!staff.esAdmin()) staff.setOrgRut("76000000-0");
        when(personal.findByEmailIgnoreCase(staff.getEmail())).thenReturn(Optional.of(staff));
        auth.pedirEnlace(null, staff.getEmail(), "127.0.0.1");
        assertEquals(1, mensajes.size());
        assertNotNull(ultimo().url());
        var sesion = porEnlace ? auth.entrarConEnlace(token(), "127.0.0.1")
                : auth.entrarConCodigoDePersonal(staff.getEmail(), ultimo().codigo(), "127.0.0.1");
        assertEquals(staff.esAdmin() ? "ADMIN" : "CREDITOR", sesion.user().role());
        assertThrows(ApiException.class, () -> auth.entrarConEnlace(token(), "127.0.0.1"));
        assertThrows(ApiException.class, () -> auth.entrarConCodigoDePersonal(staff.getEmail(), ultimo().codigo(), "127.0.0.1"));
        assertEquals(1, sesiones.filas.size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void entre_campana_y_pedido_solo_se_canjea_el_codigo_mas_reciente(boolean campanaPosterior) {
        auth.pedirCodigo(RUT, null, "127.0.0.1");
        MagicLink pedido = pedidos.getLast();
        AccessCode campana = new AccessCode();
        campana.setDebtorRut(RUT);
        campana.setCodeHash(AuthService.huella(RUT, "K7M2QX"));
        campana.setIssuedAt(pedido.getIssuedAt().plusSeconds(campanaPosterior ? 1 : -1));
        campana.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        when(codigos.findFirstByDebtorRutOrderByIssuedAtDescIdDesc(RUT)).thenReturn(Optional.of(campana));
        String vigente = campanaPosterior ? "K7M2QX" : ultimo().codigo();
        String anterior = campanaPosterior ? ultimo().codigo() : "K7M2QX";
        auth.entrarConCodigo(RUT, vigente, "127.0.0.1");
        assertThrows(ApiException.class, () -> auth.entrarConCodigo(RUT, anterior, "127.0.0.1"));
        assertEquals(1, sesiones.filas.size());
    }

    @Test
    void un_codigo_de_campana_consumido_no_se_puede_volver_a_usar() {
        AccessCode campana = new AccessCode();
        campana.setDebtorRut(RUT);
        campana.setCodeHash(AuthService.huella(RUT, "K7M2QX"));
        campana.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
        campana.consumir();
        when(codigos.findFirstByDebtorRutOrderByIssuedAtDescIdDesc(RUT)).thenReturn(Optional.of(campana));
        assertThrows(ApiException.class, () -> auth.entrarConCodigo(RUT, "K7M2QX", "127.0.0.1"));
        assertTrue(sesiones.filas.isEmpty());
    }

    @Test
    void el_codigo_no_da_acceso_si_deshabilitaron_a_la_persona() {
        StaffUser staff = new StaffUser();
        staff.setEmail("persona@example.com");
        when(personal.findByEmailIgnoreCase(staff.getEmail())).thenReturn(Optional.of(staff));
        auth.pedirCodigo(null, staff.getEmail(), "127.0.0.1");
        staff.deshabilitar();
        assertThrows(ApiException.class, () -> auth.entrarConCodigoDePersonal(staff.getEmail(), ultimo().codigo(), "127.0.0.1"));
        assertTrue(sesiones.filas.isEmpty());
        verify(bitacora, never()).save(argThat(r -> r.getOutcome() == AccessLog.Outcome.granted));
        verify(bitacora).save(argThat(r -> r.getOutcome() == AccessLog.Outcome.refused));
    }

    @Test
    void una_empresa_suspendida_no_abre_sesion_con_su_codigo() {
        StaffUser staff = new StaffUser();
        staff.setEmail("persona@example.com");
        staff.setOrgRut("76000000-0");
        when(personal.findByEmailIgnoreCase(staff.getEmail())).thenReturn(Optional.of(staff));
        auth.pedirCodigo(null, staff.getEmail(), "127.0.0.1");
        when(deudas.empresa(staff.getOrgRut())).thenReturn(Optional.of(
                new DeudasClient.Empresa(staff.getOrgRut(), "Empresa", false)));
        assertThrows(ApiException.class, () -> auth.entrarConCodigoDePersonal(staff.getEmail(), ultimo().codigo(), "127.0.0.1"));
        assertTrue(sesiones.filas.isEmpty());
    }

    @Test
    void pedir_otro_codigo_invalida_el_pedido_anterior() {
        auth.pedirEnlace(RUT, null, "127.0.0.1");
        MagicLink anterior = pedidos.getLast();
        auth.pedirCodigo(RUT, null, "127.0.0.1");
        assertNotNull(anterior.getConsumedAt());
        assertNotEquals(anterior.getCodeHash(), pedidos.getLast().getCodeHash());
        assertEquals("DEBTOR", auth.entrarConCodigo(RUT, ultimo().codigo(), "127.0.0.1").user().role());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void una_caida_de_deudas_no_consume_el_acceso_correcto(boolean porEnlace) {
        StaffUser staff = new StaffUser();
        staff.setEmail("persona@example.com");
        staff.setOrgRut("76000000-0");
        when(personal.findByEmailIgnoreCase(staff.getEmail())).thenReturn(Optional.of(staff));
        auth.pedirEnlace(null, staff.getEmail(), "127.0.0.1");
        MagicLink pedido = pedidos.getLast();
        when(deudas.empresa(staff.getOrgRut()))
                .thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Deudas no responde"))
                .thenReturn(Optional.of(new DeudasClient.Empresa(staff.getOrgRut(), "Empresa", true)));

        ApiException error = assertThrows(ApiException.class, () -> {
            if (porEnlace) auth.entrarConEnlace(token(), "127.0.0.1");
            else auth.entrarConCodigoDePersonal(staff.getEmail(), ultimo().codigo(), "127.0.0.1");
        });
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatus());
        assertNull(pedido.getConsumedAt(), "Sin sesion no se debe consumir una credencial correcta");
        assertTrue(sesiones.filas.isEmpty());
        assertEquals(0, pedido.getAttempts().intValue());
        verify(bitacora, never()).save(argThat(r -> r.getOutcome() == AccessLog.Outcome.granted));

        var sesion = porEnlace ? auth.entrarConEnlace(token(), "127.0.0.1")
                : auth.entrarConCodigoDePersonal(staff.getEmail(), ultimo().codigo(), "127.0.0.1");
        assertEquals("CREDITOR", sesion.user().role());
        assertNotNull(pedido.getConsumedAt());
        assertEquals(1, sesiones.filas.size());
    }

    @Test
    void los_intentos_fallidos_agotan_tambien_el_enlace_del_respaldo() {
        auth.pedirEnlace(RUT, null, "127.0.0.1");
        String codigo = ultimo().codigo();
        for (int i = 0; i < 5; i++) assertThrows(ApiException.class,
                () -> auth.entrarConCodigo(RUT, "000000", "127.0.0.1"));
        assertEquals(5, pedidos.getLast().getAttempts().intValue());
        ApiException agotado = assertThrows(ApiException.class, () -> auth.entrarConCodigo(RUT, codigo, "127.0.0.1"));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, agotado.getStatus());
        assertThrows(ApiException.class, () -> auth.entrarConEnlace(token(), "127.0.0.1"));
        assertTrue(sesiones.filas.isEmpty());
    }

    @Test
    void al_vencer_no_sirve_ninguna_de_las_dos_opciones() {
        auth.pedirEnlace(RUT, null, "127.0.0.1");
        pedidos.getLast().setExpiresAt(Instant.now().minus(1, ChronoUnit.SECONDS));
        assertThrows(ApiException.class, () -> auth.entrarConCodigo(RUT, ultimo().codigo(), "127.0.0.1"));
        assertThrows(ApiException.class, () -> auth.entrarConEnlace(token(), "127.0.0.1"));
        assertTrue(sesiones.filas.isEmpty());
    }

    @Test
    void el_codigo_solo_no_habilita_un_enlace_oculto() {
        auth.pedirCodigo(RUT, null, "127.0.0.1");
        when(enlaces.findByTokenHash(any())).thenReturn(Optional.of(pedidos.getLast()));
        assertThrows(ApiException.class, () -> auth.entrarConEnlace("token", "127.0.0.1"));
        assertTrue(sesiones.filas.isEmpty());
    }

    @Test
    void datos_inexistentes_tienen_la_misma_respuesta_sin_correo() {
        var respuesta = auth.pedirCodigo(RUT, null, "127.0.0.1");
        when(deudas.correoDelDeudor(RUT)).thenReturn(Optional.empty());
        var inexistente = auth.pedirCodigo(RUT, null, "127.0.0.1");
        assertEquals(respuesta, inexistente);
        assertEquals(1, mensajes.size());
    }

    @Test
    void el_destinatario_del_deudor_no_se_puede_elegir_desde_la_peticion() {
        auth.pedirEnlace(RUT, "ajeno@example.com", "127.0.0.1");
        assertEquals("deudor@example.com", ultimo().para());
    }

    @Test
    void un_mismo_correo_de_deudor_y_personal_no_mezcla_sus_identidades() {
        StaffUser staff = new StaffUser();
        staff.setEmail("deudor@example.com");
        staff.setRole(StaffUser.Role.admin);
        when(personal.findByEmailIgnoreCase(staff.getEmail())).thenReturn(Optional.of(staff));
        auth.pedirEnlace(RUT, null, "127.0.0.1");
        assertEquals("DEBTOR", auth.entrarConEnlace(token(), "127.0.0.1").user().role());
    }

    @Test
    void no_se_entrega_el_correo_antes_del_commit() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            auth.pedirEnlace(RUT, null, "127.0.0.1");
            assertTrue(mensajes.isEmpty());
            TransactionSynchronizationManager.getSynchronizations().forEach(s -> s.afterCommit());
            assertEquals(1, mensajes.size());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
