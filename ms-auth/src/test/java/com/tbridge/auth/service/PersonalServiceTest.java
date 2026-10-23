package com.tbridge.auth.service;

import com.tbridge.auth.client.DeudasClient;
import com.tbridge.auth.dto.request.NuevaPersonaRequest;
import com.tbridge.auth.dto.response.PersonaResponse;
import com.tbridge.auth.exception.ApiException;
import com.tbridge.auth.model.AdminLog;
import com.tbridge.auth.model.MagicLink;
import com.tbridge.auth.model.StaffUser;
import com.tbridge.auth.repository.AdminLogRepository;
import com.tbridge.auth.repository.MagicLinkRepository;
import com.tbridge.auth.repository.StaffUserRepository;
import com.tbridge.auth.security.JwtPrincipal;
import com.tbridge.auth.util.Hash;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Las personas en el panel de administracion: el admin de DataBridge da de
 * alta a la gente de una empresa y a otros admin, y les quita o devuelve el
 * acceso. Una persona de una empresa nunca es admin, y cada cambio queda
 * anotado.
 */
class PersonalServiceTest {

    private static final String EMPRESA = "76086428-5";
    private static final JwtPrincipal ADMIN =
            new JwtPrincipal("9", "admin@databridge.cl", "ADMIN", "Administrador de DataBridge", null);

    private final List<StaffUser> guardadas = new ArrayList<>();
    private final List<AdminLog> anotado = new ArrayList<>();
    private final List<MagicLink> enlaces = new ArrayList<>();
    private StaffUserRepository personal;
    private MagicLinkRepository enlacesRepo;
    private SessionService sesiones;
    private DeudasClient deudas;
    private MailService correo;
    private PersonalService servicio;

    @BeforeEach
    void preparar() {
        personal = mock(StaffUserRepository.class);
        AdminLogRepository bitacora = mock(AdminLogRepository.class);
        enlacesRepo = mock(MagicLinkRepository.class);
        sesiones = mock(SessionService.class);
        deudas = mock(DeudasClient.class);
        correo = mock(MailService.class);

        when(personal.saveAndFlush(any())).thenAnswer(llamada -> {
            StaffUser persona = llamada.getArgument(0);
            ReflectionTestUtils.setField(persona, "id", 20L + guardadas.size());
            guardadas.add(persona);
            return persona;
        });
        when(personal.save(any())).thenAnswer(llamada -> llamada.getArgument(0));
        when(personal.findByEmailIgnoreCase(any())).thenAnswer(llamada -> guardadas.stream()
                .filter(p -> p.getEmail().equalsIgnoreCase(llamada.getArgument(0))).findFirst());
        when(personal.findById(anyLong())).thenAnswer(llamada -> guardadas.stream()
                .filter(p -> p.getId().equals(llamada.getArgument(0))).findFirst());
        when(bitacora.save(any())).thenAnswer(llamada -> {
            anotado.add(llamada.getArgument(0));
            return llamada.getArgument(0);
        });
        when(enlacesRepo.save(any())).thenAnswer(llamada -> {
            enlaces.add(llamada.getArgument(0));
            return llamada.getArgument(0);
        });
        when(deudas.empresa(EMPRESA)).thenReturn(Optional.of(new DeudasClient.Empresa(EMPRESA, "Empresa Test", true)));

        servicio = new PersonalService(personal, bitacora, enlacesRepo, sesiones, deudas, correo,
                "http://localhost:8080/", 24);
    }

    private static NuevaPersonaRequest ana() {
        return new NuevaPersonaRequest("  Ana.Soto@EmpresaTest.cl ", " Ana Soto ");
    }

    private StaffUser yaExiste(String correo, StaffUser.Role rol, String orgRut) {
        StaffUser persona = new StaffUser();
        ReflectionTestUtils.setField(persona, "id", 5L + guardadas.size());
        persona.setEmail(correo);
        persona.setFullName("Alguien");
        persona.setRole(rol);
        persona.setOrgRut(orgRut);
        guardadas.add(persona);
        return persona;
    }

    private HttpStatus estado(Runnable accion) {
        return assertThrows(ApiException.class, accion::run).getStatus();
    }

    // ------------------------------------------------------------------
    //  Dar de alta a una persona de una empresa
    // ------------------------------------------------------------------

    @Test
    void la_persona_de_una_empresa_queda_como_usuario_de_esa_empresa() {
        PersonaResponse creada = servicio.crearUsuario("76.086.428-5", ana(), ADMIN);

        assertEquals("usuario", creada.rol());
        assertEquals(EMPRESA, creada.empresaRut());
        assertEquals("ana.soto@empresatest.cl", creada.correo(), "el correo se guarda de una sola forma");
        assertEquals("Ana Soto", creada.nombre());
        assertTrue(creada.habilitada());
        assertNull(creada.ultimoAcceso(), "todavia no entra");
    }

    @Test
    void le_llega_una_invitacion_con_un_enlace_de_un_solo_uso_que_dura_horas() {
        Instant antes = Instant.now();
        servicio.crearUsuario(EMPRESA, ana(), ADMIN);

        ArgumentCaptor<String> url = ArgumentCaptor.forClass(String.class);
        verify(correo).enviarInvitacion(eq("ana.soto@empresatest.cl"), eq("Ana Soto"),
                eq("al portal de empresas, como parte de Empresa Test"), url.capture(), eq(24L),
                eq("http://localhost:8080/databridge/login"));

        //  El enlace del correo es el que quedo guardado, y solo su huella.
        MagicLink enlace = enlaces.getFirst();
        String token = url.getValue().substring("http://localhost:8080/magic?token=".length());
        assertTrue(url.getValue().startsWith("http://localhost:8080/magic?token="));
        assertEquals(Hash.sha256(token), ReflectionTestUtils.getField(enlace, "tokenHash"));
        assertEquals("ana.soto@empresatest.cl", enlace.getEmail());
        assertNull(enlace.getDebtorRut(), "es de personal, no de un deudor");
        Duration vida = Duration.between(antes, enlace.getExpiresAt());
        assertTrue(vida.compareTo(Duration.ofHours(23)) > 0 && vida.compareTo(Duration.ofHours(25)) < 0, vida.toString());
    }

    @Test
    void queda_anotado_quien_la_dio_de_alta() {
        servicio.crearUsuario(EMPRESA, ana(), ADMIN);

        assertEquals(1, anotado.size());
        assertEquals(AdminLog.Action.persona_creada, anotado.getFirst().getAction());
        assertEquals("admin@databridge.cl", anotado.getFirst().getAdminEmail());
        assertEquals(EMPRESA, anotado.getFirst().getOrgRut());
        assertEquals("ana.soto@empresatest.cl", anotado.getFirst().getSubject());
        assertNotNull(anotado.getFirst().getOccurredAt());
    }

    @Test
    void sin_la_empresa_en_ms_debt_no_se_crea_a_nadie() {
        when(deudas.empresa("77305118-6")).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND, estado(() -> servicio.crearUsuario("77305118-6", ana(), ADMIN)));
        verify(personal, never()).saveAndFlush(any());
        verify(correo, never()).enviarInvitacion(any(), any(), any(), any(), anyLong(), any());
        assertTrue(anotado.isEmpty());
    }

    @Test
    void si_ms_debt_no_responde_tampoco_y_el_error_llega_tal_cual() {
        when(deudas.empresa(EMPRESA)).thenThrow(new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "no responde"));

        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, estado(() -> servicio.crearUsuario(EMPRESA, ana(), ADMIN)));
        verify(personal, never()).saveAndFlush(any());
    }

    @Test
    void a_una_empresa_suspendida_no_se_le_agregan_personas() {
        when(deudas.empresa(EMPRESA)).thenReturn(Optional.of(new DeudasClient.Empresa(EMPRESA, "Empresa Test", false)));

        assertEquals(HttpStatus.CONFLICT, estado(() -> servicio.crearUsuario(EMPRESA, ana(), ADMIN)));
        verify(personal, never()).saveAndFlush(any());
    }

    @Test
    void un_rut_o_un_correo_que_no_son_validos_se_rechazan_antes_de_preguntar_nada() {
        assertEquals(HttpStatus.BAD_REQUEST, estado(() -> servicio.crearUsuario("76086428-9", ana(), ADMIN)));
        assertEquals(HttpStatus.BAD_REQUEST, estado(() -> servicio.crearUsuario(EMPRESA,
                new NuevaPersonaRequest("ana@empresatest", "Ana Soto"), ADMIN)));
        verify(deudas, never()).empresa(any());
        verify(personal, never()).saveAndFlush(any());
    }

    @Test
    void un_correo_que_ya_tiene_cuenta_se_rechaza_diciendo_de_quien_es() {
        yaExiste("ana.soto@empresatest.cl", StaffUser.Role.usuario, EMPRESA);
        yaExiste("camila.reyes@apofyx.cl", StaffUser.Role.usuario, "77305118-6");
        yaExiste("admin@databridge.cl", StaffUser.Role.admin, null);

        ApiException misma = assertThrows(ApiException.class, () -> servicio.crearUsuario(EMPRESA, ana(), ADMIN));
        assertEquals(HttpStatus.CONFLICT, misma.getStatus());
        assertTrue(misma.getMessage().contains("ya esta en esta empresa"));

        ApiException otra = assertThrows(ApiException.class, () -> servicio.crearUsuario(EMPRESA,
                new NuevaPersonaRequest("CAMILA.REYES@apofyx.cl", "Camila Reyes"), ADMIN));
        assertTrue(otra.getMessage().contains("otra empresa"));

        ApiException admin = assertThrows(ApiException.class, () -> servicio.crearUsuario(EMPRESA,
                new NuevaPersonaRequest("admin@databridge.cl", "Admin"), ADMIN));
        assertTrue(admin.getMessage().contains("un admin no es de ninguna empresa"));

        verify(personal, never()).saveAndFlush(any());
        assertTrue(anotado.isEmpty());
    }

    @Test
    void dos_altas_del_mismo_correo_a_la_vez_dejan_pasar_una_sola() {
        doThrow(new DataIntegrityViolationException("uq_staff_user_email")).when(personal).saveAndFlush(any());

        assertEquals(HttpStatus.CONFLICT, estado(() -> servicio.crearUsuario(EMPRESA, ana(), ADMIN)));
        verify(correo, never()).enviarInvitacion(any(), any(), any(), any(), anyLong(), any());
        assertTrue(anotado.isEmpty());
    }

    // ------------------------------------------------------------------
    //  Dar de alta a otro admin
    // ------------------------------------------------------------------

    @Test
    void otro_admin_de_databridge_queda_sin_empresa_y_entra_por_el_panel() {
        PersonaResponse creado = servicio.crearAdmin(new NuevaPersonaRequest("otra@databridge.cl", "Otra Persona"), ADMIN);

        assertEquals("admin", creado.rol());
        assertNull(creado.empresaRut());
        verify(correo).enviarInvitacion(eq("otra@databridge.cl"), eq("Otra Persona"),
                eq("al panel de administración"), anyString(), eq(24L), eq("http://localhost:8080/admin/login"));
        assertEquals(AdminLog.Action.admin_creado, anotado.getFirst().getAction());
        assertNull(anotado.getFirst().getOrgRut());
        //  Un admin no es de ninguna empresa: no hay nada que preguntarle a ms-debt.
        verify(deudas, never()).empresa(any());
    }

    @Test
    void a_una_persona_de_una_empresa_no_se_le_puede_dar_el_rol_admin() {
        yaExiste("camila.reyes@apofyx.cl", StaffUser.Role.usuario, "77305118-6");

        ApiException error = assertThrows(ApiException.class, () -> servicio.crearAdmin(
                new NuevaPersonaRequest("camila.reyes@apofyx.cl", "Camila Reyes"), ADMIN));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertTrue(error.getMessage().contains("no se le puede dar el rol admin"));
        assertEquals(StaffUser.Role.usuario, guardadas.getFirst().getRole());
        verify(personal, never()).saveAndFlush(any());
    }

    // ------------------------------------------------------------------
    //  Quitar y devolver el acceso
    // ------------------------------------------------------------------

    @Test
    void quitarle_el_acceso_a_alguien_cierra_sus_sesiones_y_anula_sus_enlaces_sin_abrir() {
        StaffUser ana = yaExiste("ana.soto@empresatest.cl", StaffUser.Role.usuario, EMPRESA);

        PersonaResponse despues = servicio.deshabilitar(ana.getId(), ADMIN);

        assertFalse(despues.habilitada());
        assertNotNull(despues.deshabilitadaEl());
        verify(sesiones).revocarDe("ana.soto@empresatest.cl");
        verify(enlacesRepo).anularPendientesDe(eq("ana.soto@empresatest.cl"), any());
        assertEquals(AdminLog.Action.persona_deshabilitada, anotado.getFirst().getAction());
        assertEquals(EMPRESA, anotado.getFirst().getOrgRut());
        assertEquals("admin@databridge.cl", anotado.getFirst().getAdminEmail());
    }

    @Test
    void la_cuenta_no_se_borra_y_se_le_puede_devolver_el_acceso() {
        StaffUser ana = yaExiste("ana.soto@empresatest.cl", StaffUser.Role.usuario, EMPRESA);
        servicio.deshabilitar(ana.getId(), ADMIN);

        PersonaResponse despues = servicio.habilitar(ana.getId(), ADMIN);

        assertTrue(despues.habilitada());
        assertNull(despues.deshabilitadaEl());
        assertEquals(List.of(AdminLog.Action.persona_deshabilitada, AdminLog.Action.persona_habilitada),
                anotado.stream().map(AdminLog::getAction).toList());
        //  Devolverle el acceso no le manda nada: pide su enlace cuando quiera entrar.
        verify(correo, never()).enviarInvitacion(any(), any(), any(), any(), anyLong(), any());
    }

    @Test
    void repetir_lo_mismo_no_cambia_nada_ni_se_anota_dos_veces() {
        StaffUser ana = yaExiste("ana.soto@empresatest.cl", StaffUser.Role.usuario, EMPRESA);

        servicio.habilitar(ana.getId(), ADMIN);
        assertTrue(anotado.isEmpty(), "ya estaba habilitada");

        servicio.deshabilitar(ana.getId(), ADMIN);
        Instant primera = ana.getDisabledAt();
        servicio.deshabilitar(ana.getId(), ADMIN);

        assertEquals(1, anotado.size());
        assertEquals(primera, ana.getDisabledAt(), "se conserva desde cuando esta deshabilitada");
    }

    @Test
    void un_admin_no_se_puede_quitar_el_acceso_a_si_mismo() {
        StaffUser yo = yaExiste("admin@databridge.cl", StaffUser.Role.admin, null);
        JwtPrincipal sesion = new JwtPrincipal(String.valueOf(yo.getId()), yo.getEmail(), "ADMIN", "Admin", null);

        ApiException error = assertThrows(ApiException.class, () -> servicio.deshabilitar(yo.getId(), sesion));

        assertEquals(HttpStatus.CONFLICT, error.getStatus());
        assertTrue(yo.habilitado(), "siempre queda al menos un admin que puede entrar");
        verify(sesiones, never()).revocarDe(any());
        assertTrue(anotado.isEmpty());
    }

    @Test
    void un_admin_si_le_puede_quitar_el_acceso_a_otro() {
        StaffUser otro = yaExiste("otra@databridge.cl", StaffUser.Role.admin, null);

        assertFalse(servicio.deshabilitar(otro.getId(), ADMIN).habilitada());
        assertNull(anotado.getFirst().getOrgRut());
    }

    @Test
    void una_persona_que_no_existe_es_404() {
        assertEquals(HttpStatus.NOT_FOUND, estado(() -> servicio.deshabilitar(999L, ADMIN)));
        assertEquals(HttpStatus.NOT_FOUND, estado(() -> servicio.habilitar(999L, ADMIN)));
    }

    // ------------------------------------------------------------------
    //  Ver
    // ------------------------------------------------------------------

    @Test
    void las_personas_de_una_empresa_se_piden_por_su_rut_en_forma_canonica() {
        StaffUser ana = yaExiste("ana.soto@empresatest.cl", StaffUser.Role.usuario, EMPRESA);
        when(personal.findByOrgRutOrderByFullNameAsc(EMPRESA)).thenReturn(List.of(ana));

        List<PersonaResponse> personas = servicio.deEmpresa("76.086.428-5");

        assertEquals(1, personas.size());
        assertEquals("ana.soto@empresatest.cl", personas.getFirst().correo());
        assertEquals(HttpStatus.BAD_REQUEST, estado(() -> servicio.deEmpresa("no-es-un-rut")));
    }
}
