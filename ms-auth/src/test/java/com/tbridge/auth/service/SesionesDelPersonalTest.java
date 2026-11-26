package com.tbridge.auth.service;

import com.tbridge.auth.dto.response.SesionAbiertaResponse;
import com.tbridge.auth.dto.response.SesionCerradaResponse;
import com.tbridge.auth.exception.ApiException;
import com.tbridge.auth.model.AdminLog;
import com.tbridge.auth.model.Session;
import com.tbridge.auth.model.StaffUser;
import com.tbridge.auth.repository.AdminLogRepository;
import com.tbridge.auth.repository.SessionRepository;
import com.tbridge.auth.repository.StaffUserRepository;
import com.tbridge.auth.security.JwtPrincipal;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Las sesiones del personal en el panel (#439): verlas sin datos de mas y cerrarlas a distancia. */
class SesionesDelPersonalTest {

    private static final Instant AHORA = Instant.parse("2026-11-19T15:00:00Z");
    private static final String ANA = "ana.soto@empresatest.cl";
    private static final String ADMIN = "admin@databridge.cl";

    private final SessionRepository sesiones = mock(SessionRepository.class);
    private final StaffUserRepository personal = mock(StaffUserRepository.class);
    private final AdminLogRepository bitacora = mock(AdminLogRepository.class);
    private final SesionesDelPersonal servicio = new SesionesDelPersonal(sesiones, personal, bitacora,
            Duration.ofMinutes(15), Clock.fixed(AHORA, ZoneOffset.UTC));
    private final JwtPrincipal admin = new JwtPrincipal("9", ADMIN, "ADMIN", "Admin", null);

    private static Session llave(String familia, Session.Role rol, String correo, Instant emitida) {
        Session sesion = new Session();
        sesion.setFamilyId(familia);
        sesion.setRefreshHash("h-" + familia + emitida);
        sesion.setRole(rol);
        sesion.setEmail(correo);
        sesion.setIssuedAt(emitida);
        sesion.setExpiresAt(Instant.parse("2026-11-26T12:00:00Z"));
        sesion.setIpHash("ip-que-no-debe-salir");
        return sesion;
    }

    private static StaffUser persona(String correo, String nombre, String empresa, StaffUser.Role rol) {
        StaffUser persona = new StaffUser();
        persona.setEmail(correo);
        persona.setFullName(nombre);
        persona.setOrgRut(empresa);
        persona.setRole(rol);
        return persona;
    }

    private static SessionRepository.InicioDeFamilia inicio(String familia, Instant cuando) {
        return new SessionRepository.InicioDeFamilia() {
            public String getFamilia() { return familia; }
            public Instant getInicio() { return cuando; }
        };
    }

    @Test
    void lista_las_sesiones_del_personal_con_su_inicio_su_renovacion_y_la_propia_marcada() {
        Session deAna = llave("f-ana", Session.Role.CREDITOR, ANA, Instant.parse("2026-11-19T14:00:00Z"));
        Session delAdmin = llave("f-admin", Session.Role.ADMIN, ADMIN, Instant.parse("2026-11-19T10:00:00Z"));
        when(sesiones.abiertas(anyCollection(), eq(AHORA), any(Pageable.class)))
                .thenAnswer(llamada -> new PageImpl<>(List.of(deAna, delAdmin), llamada.getArgument(2), 2));
        when(sesiones.iniciosDe(anyCollection())).thenReturn(List.of(
                inicio("f-ana", Instant.parse("2026-11-19T09:00:00Z")),
                inicio("f-admin", Instant.parse("2026-11-19T10:00:00Z"))));
        when(personal.findByEmailIn(anyCollection())).thenReturn(List.of(
                persona(ANA, "Ana Soto", "76086428-5", StaffUser.Role.usuario),
                persona(ADMIN, "Admin", null, StaffUser.Role.admin)));

        List<SesionAbiertaResponse> filas = servicio.abiertas(0, 20, admin).getContent();

        SesionAbiertaResponse ana = filas.get(0);
        assertThat(ana.familia()).isEqualTo("f-ana");
        assertThat(ana.nombre()).isEqualTo("Ana Soto");
        assertThat(ana.rol()).isEqualTo("usuario");
        assertThat(ana.empresaRut()).isEqualTo("76086428-5");
        assertThat(ana.desde()).isEqualTo(Instant.parse("2026-11-19T09:00:00Z"));
        assertThat(ana.renovada()).isEqualTo(Instant.parse("2026-11-19T14:00:00Z"));
        assertThat(ana.tuya()).isFalse();
        assertThat(ana.minutosDeAcceso()).isEqualTo(15);

        SesionAbiertaResponse propia = filas.get(1);
        assertThat(propia.rol()).isEqualTo("admin");
        assertThat(propia.empresaRut()).isNull();
        assertThat(propia.renovada()).as("la llave viva es la de entrar: no ha renovado").isNull();
        assertThat(propia.tuya()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void pide_solo_las_del_personal_vivas_ahora_la_renovada_hace_menos_primero_y_hasta_50() {
        when(sesiones.abiertas(anyCollection(), any(), any(Pageable.class)))
                .thenAnswer(llamada -> Page.empty(llamada.getArgument(2)));

        servicio.abiertas(2, 500, admin);

        ArgumentCaptor<Collection<Session.Role>> roles = ArgumentCaptor.forClass(Collection.class);
        ArgumentCaptor<Pageable> pagina = ArgumentCaptor.forClass(Pageable.class);
        verify(sesiones).abiertas(roles.capture(), eq(AHORA), pagina.capture());
        assertThat(roles.getValue()).containsExactlyInAnyOrder(Session.Role.CREDITOR, Session.Role.ADMIN);
        assertThat(pagina.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pagina.getValue().getPageSize()).isEqualTo(SesionesDelPersonal.MAXIMO_POR_PAGINA);
        assertThat(pagina.getValue().getSort().getOrderFor("issuedAt").isDescending()).isTrue();
        //  Sin sesiones no se consulta nada mas: un IN vacio no es SQL valido.
        verify(sesiones, never()).iniciosDe(any());
        verify(personal, never()).findByEmailIn(any());
    }

    @Test
    void una_pagina_negativa_es_400() {
        assertThatThrownBy(() -> servicio.abiertas(-1, 20, admin))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void cerrar_revoca_la_familia_lo_anota_y_dice_que_el_acceso_dura_hasta_que_vence_el_jwt() {
        Session viva = llave("f-ana", Session.Role.CREDITOR, ANA, Instant.parse("2026-11-19T14:00:00Z"));
        when(sesiones.findFirstByFamilyIdOrderByIdDesc("f-ana")).thenReturn(Optional.of(viva));
        when(personal.findByEmailIgnoreCase(ANA))
                .thenReturn(Optional.of(persona(ANA, "Ana Soto", "76086428-5", StaffUser.Role.usuario)));

        SesionCerradaResponse cerrada = servicio.cerrar("f-ana", admin);

        verify(sesiones).revocarFamilia("f-ana", AHORA);
        assertThat(cerrada.correo()).isEqualTo(ANA);
        assertThat(cerrada.accesoHasta()).isEqualTo(AHORA.plus(Duration.ofMinutes(15)));
        ArgumentCaptor<AdminLog> registro = ArgumentCaptor.forClass(AdminLog.class);
        verify(bitacora).save(registro.capture());
        assertThat(registro.getValue().getAction()).isEqualTo(AdminLog.Action.sesion_cerrada);
        assertThat(registro.getValue().getAdminEmail()).isEqualTo(ADMIN);
        assertThat(registro.getValue().getOrgRut()).isEqualTo("76086428-5");
        assertThat(registro.getValue().getSubject()).isEqualTo(ANA);
    }

    @Test
    void la_sesion_de_un_admin_queda_anotada_sin_empresa() {
        Session viva = llave("f-otro", Session.Role.ADMIN, "otro@databridge.cl", Instant.parse("2026-11-19T14:00:00Z"));
        when(sesiones.findFirstByFamilyIdOrderByIdDesc("f-otro")).thenReturn(Optional.of(viva));
        when(personal.findByEmailIgnoreCase("otro@databridge.cl"))
                .thenReturn(Optional.of(persona("otro@databridge.cl", "Otro", null, StaffUser.Role.admin)));

        servicio.cerrar("f-otro", admin);

        ArgumentCaptor<AdminLog> registro = ArgumentCaptor.forClass(AdminLog.class);
        verify(bitacora).save(registro.capture());
        assertThat(registro.getValue().getOrgRut()).isNull();
    }

    @Test
    void la_sesion_de_un_deudor_o_una_que_no_existe_es_404_y_no_se_toca() {
        Session deDeudor = llave("f-deudor", Session.Role.DEBTOR, null, Instant.parse("2026-11-19T14:00:00Z"));
        when(sesiones.findFirstByFamilyIdOrderByIdDesc("f-deudor")).thenReturn(Optional.of(deDeudor));
        when(sesiones.findFirstByFamilyIdOrderByIdDesc("f-nada")).thenReturn(Optional.empty());

        for (String familia : List.of("f-deudor", "f-nada")) {
            assertThatThrownBy(() -> servicio.cerrar(familia, admin))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        }
        verify(sesiones, never()).revocarFamilia(any(), any());
        verify(bitacora, never()).save(any());
    }

    @Test
    void una_sesion_ya_cerrada_rotada_o_vencida_es_409_y_no_se_anota() {
        Session revocada = llave("f-1", Session.Role.CREDITOR, ANA, Instant.parse("2026-11-19T14:00:00Z"));
        revocada.setRevokedAt(Instant.parse("2026-11-19T14:30:00Z"));
        Session rotada = llave("f-2", Session.Role.CREDITOR, ANA, Instant.parse("2026-11-19T14:00:00Z"));
        rotada.setRotatedAt(Instant.parse("2026-11-19T14:30:00Z"));
        Session vencida = llave("f-3", Session.Role.CREDITOR, ANA, Instant.parse("2026-11-12T14:00:00Z"));
        vencida.setExpiresAt(Instant.parse("2026-11-19T14:00:00Z"));
        when(sesiones.findFirstByFamilyIdOrderByIdDesc("f-1")).thenReturn(Optional.of(revocada));
        when(sesiones.findFirstByFamilyIdOrderByIdDesc("f-2")).thenReturn(Optional.of(rotada));
        when(sesiones.findFirstByFamilyIdOrderByIdDesc("f-3")).thenReturn(Optional.of(vencida));

        for (String familia : List.of("f-1", "f-2", "f-3")) {
            assertThatThrownBy(() -> servicio.cerrar(familia, admin))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getStatus()).isEqualTo(HttpStatus.CONFLICT);
        }
        verify(sesiones, never()).revocarFamilia(any(), any());
        verify(bitacora, never()).save(any());
    }
}
