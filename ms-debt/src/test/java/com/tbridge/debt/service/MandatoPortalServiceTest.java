package com.tbridge.debt.service;

import com.tbridge.debt.dto.request.MandatoPortalRequest;
import com.tbridge.debt.dto.request.RenovarMandatoRequest;
import com.tbridge.debt.dto.response.MandatoPortalResponse;
import com.tbridge.debt.dto.response.MandatosResponse;
import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.model.Mandate;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.CampaignRepository;
import com.tbridge.debt.repository.DebtRepository;
import com.tbridge.debt.repository.MandateRepository;
import com.tbridge.debt.repository.OrganizationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.http.HttpStatus;

import java.math.BigDecimal;
import java.time.LocalDate;
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
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * La agencia declara, renueva y termina sus mandatos en el portal (#234): con la
 * API apagada, es por donde nace un mandato.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MandatoPortalServiceTest {

    private static final String CAMILA = "camila.reyes@apofyx.cl";

    @Mock private MandateRepository mandates;
    @Mock private OrganizationRepository organizations;
    @Mock private CampaignRepository campaigns;
    @Mock private DebtRepository debts;

    private MandatoPortalService servicio;
    private Organization apofyx;
    private Organization patrimonio;
    private final List<Mandate> guardados = new ArrayList<>();
    private final LocalDate hoy = LocalDate.now();

    @BeforeEach
    void preparar() {
        MandatoService contrato = new MandatoService(organizations, mandates, campaigns,
                new DescuentoService(mandates, new com.fasterxml.jackson.databind.ObjectMapper()));
        servicio = new MandatoPortalService(mandates, organizations, debts, contrato, 30);

        apofyx = empresa(1L, "77305118-6", "APOFYX", Organization.Kind.agency);
        patrimonio = empresa(2L, "76418902-7", "Patrimonio Inmuebles", Organization.Kind.creditor);
        when(organizations.findByRut("76418902-7")).thenReturn(Optional.of(patrimonio));
        when(organizations.findByRut("76543210-3")).thenReturn(Optional.empty());
        when(organizations.save(any(Organization.class))).thenAnswer(llamada -> {
            Organization nueva = llamada.getArgument(0);
            nueva.setId(3L);
            return nueva;
        });
        when(mandates.deLaAgencia(apofyx)).thenReturn(guardados);
        when(mandates.save(any(Mandate.class))).thenAnswer(llamada -> llamada.getArgument(0));
    }

    private static Organization empresa(Long id, String rut, String nombre, Organization.Kind tipo) {
        Organization empresa = new Organization();
        empresa.setId(id);
        empresa.setRut(rut);
        empresa.setTradeName(nombre);
        empresa.setLegalName(nombre + " SpA");
        empresa.setKind(tipo);
        return empresa;
    }

    private static MandatoPortalRequest pedido(String rut, String razonSocial, String desde, String hasta,
                                               Integer mora, BigDecimal descuento) {
        return new MandatoPortalRequest(rut, razonSocial, null, desde, hasta, mora, descuento);
    }

    private Mandate yaTiene(long id, LocalDate desde, LocalDate hasta, Mandate.Status estado) {
        Mandate mandato = new Mandate();
        mandato.setId(id);
        mandato.setAgency(apofyx);
        mandato.setCreditor(patrimonio);
        mandato.setValidFrom(desde);
        mandato.setValidTo(hasta);
        mandato.setStatus(estado);
        guardados.add(mandato);
        when(mandates.findByIdAndAgency(id, apofyx)).thenReturn(Optional.of(mandato));
        return mandato;
    }

    private HttpStatus falla(Runnable accion, String mensaje) {
        ApiException error = assertThrows(ApiException.class, accion::run);
        assertEquals(mensaje, error.getMessage());
        return error.getStatus();
    }

    // ------------------------------------------------------------------
    //  Declarar
    // ------------------------------------------------------------------

    @Test
    void la_agencia_declara_un_mandato_y_queda_vigente_con_quien_lo_declaro() {
        MandatoPortalResponse mandato = servicio.declarar(apofyx, CAMILA,
                pedido("76.418.902-7", null, null, null, null, new BigDecimal("50")));

        ArgumentCaptor<Mandate> guardado = ArgumentCaptor.forClass(Mandate.class);
        verify(mandates).save(guardado.capture());
        assertEquals(apofyx, guardado.getValue().getAgency());
        assertEquals(patrimonio, guardado.getValue().getCreditor());
        assertEquals(hoy, guardado.getValue().getValidFrom(), "sin desde, hoy");
        assertNull(guardado.getValue().getValidTo(), "sin hasta, sin fecha de termino");
        assertEquals((short) 120, guardado.getValue().getMaxOverdueDays());
        assertEquals(CAMILA, guardado.getValue().getDeclaredBy(), "la persona, no solo la agencia");
        assertEquals("vigente", mandato.estado());
        assertEquals("Patrimonio Inmuebles", mandato.acreedor());
        assertEquals(new BigDecimal("50"), mandato.descuentoMaximoMora());
        verify(organizations, never()).save(any());
    }

    @Test
    void un_acreedor_que_databridge_no_conoce_se_registra_y_sin_razon_social_no() {
        assertEquals(HttpStatus.BAD_REQUEST, falla(() -> servicio.declarar(apofyx, CAMILA,
                        pedido("76543210-3", " ", null, null, null, null)),
                "DataBridge todavía no conoce a ese acreedor: escribe su razón social"));
        verify(organizations, never()).save(any());

        MandatoPortalResponse mandato = servicio.declarar(apofyx, CAMILA,
                pedido("76543210-3", "Instituto Andes SpA", null, null, null, null));

        ArgumentCaptor<Organization> nueva = ArgumentCaptor.forClass(Organization.class);
        verify(organizations).save(nueva.capture());
        assertEquals("Instituto Andes SpA", nueva.getValue().getLegalName());
        assertEquals(Organization.Kind.creditor, nueva.getValue().getKind());
        assertEquals("76543210-3", mandato.acreedorRut());
    }

    @Test
    void una_empresa_que_cobra_lo_suyo_no_declara_mandatos_ni_los_ve() {
        Organization andes = empresa(4L, "76543210-3", "Instituto Andes", Organization.Kind.creditor);

        assertEquals(HttpStatus.FORBIDDEN, falla(() -> servicio.declarar(andes, "ana@andes.cl",
                        pedido("76418902-7", null, null, null, null, null)),
                "Los mandatos son de las agencias de cobranza: tu empresa cobra lo suyo directo"));
        MandatosResponse lista = servicio.listar(andes);

        assertFalse(lista.agencia());
        assertTrue(lista.mandatos().isEmpty());
        verify(mandates, never()).save(any());
    }

    @Test
    void lo_que_no_sirve_se_rechaza_con_un_mensaje_que_dice_que_corregir() {
        String manana = hoy.plusDays(1).toString();
        String ayer = hoy.minusDays(1).toString();
        assertEquals(HttpStatus.BAD_REQUEST, falla(() -> servicio.declarar(apofyx, CAMILA,
                pedido("76418902-0", null, null, null, null, null)), "El RUT del acreedor no es válido"));
        assertEquals(HttpStatus.BAD_REQUEST, falla(() -> servicio.declarar(apofyx, CAMILA,
                pedido("77305118-6", null, null, null, null, null)), "Una agencia no puede tener un mandato sobre sí misma"));
        assertEquals(HttpStatus.BAD_REQUEST, falla(() -> servicio.declarar(apofyx, CAMILA,
                pedido("76418902-7", null, "01-10-2026", null, null, null)), "La fecha de inicio no es válida"));
        assertEquals(HttpStatus.BAD_REQUEST, falla(() -> servicio.declarar(apofyx, CAMILA,
                pedido("76418902-7", null, manana, hoy.toString(), null, null)), "El término no puede ser antes del inicio"));
        assertEquals(HttpStatus.BAD_REQUEST, falla(() -> servicio.declarar(apofyx, CAMILA,
                pedido("76418902-7", null, "2026-01-01", ayer, null, null)), "Esa fecha de término ya pasó: pon hoy o una posterior"));
        assertEquals(HttpStatus.BAD_REQUEST, falla(() -> servicio.declarar(apofyx, CAMILA,
                pedido("76418902-7", null, null, ayer, null, null)), "Esa fecha de término ya pasó: pon hoy o una posterior"));
        assertEquals(HttpStatus.BAD_REQUEST, falla(() -> servicio.declarar(apofyx, CAMILA,
                        pedido("76418902-7", null, null, null, 29, null)),
                "La mora máxima va de 30 a 3650 días: con menos de 30 no entra ninguna deuda"));
        assertEquals(HttpStatus.BAD_REQUEST, falla(() -> servicio.declarar(apofyx, CAMILA,
                pedido("76418902-7", null, null, null, null, new BigDecimal("100.5"))), "El descuento máximo es un % de 0 a 100"));
        verify(mandates, never()).save(any());
    }

    @Test
    void con_un_mandato_vigente_o_por_empezar_no_se_declara_otro_con_el_mismo_acreedor() {
        yaTiene(9L, hoy.plusDays(10), null, Mandate.Status.active);

        assertEquals(HttpStatus.CONFLICT, falla(() -> servicio.declarar(apofyx, CAMILA,
                        pedido("76418902-7", null, null, null, null, null)),
                "Ya tienes un mandato con Patrimonio Inmuebles: renuévalo o termínalo"));
        verify(mandates, never()).save(any());
    }

    @Test
    void con_el_anterior_vencido_o_terminado_se_declara_uno_nuevo_pero_no_el_mismo_dia() {
        yaTiene(9L, LocalDate.of(2026, 1, 1), hoy.minusDays(1), Mandate.Status.active);
        yaTiene(8L, LocalDate.of(2025, 6, 1), null, Mandate.Status.ended);

        assertEquals(HttpStatus.CONFLICT, falla(() -> servicio.declarar(apofyx, CAMILA,
                        pedido("76418902-7", null, "2026-01-01", null, null, null)),
                "Ya hubo un mandato con Patrimonio Inmuebles que empezaba ese día: elige otra fecha de inicio"));
        assertEquals("vigente", servicio.declarar(apofyx, CAMILA, pedido("76418902-7", null, null, null, null, null)).estado());
    }

    // ------------------------------------------------------------------
    //  Ver, renovar y terminar
    // ------------------------------------------------------------------

    @Test
    void la_agencia_ve_cada_mandato_con_su_estado_y_lo_que_sigue_en_gestion() {
        yaTiene(1L, hoy.minusDays(30), null, Mandate.Status.active);
        yaTiene(2L, hoy.plusDays(5), null, Mandate.Status.active);
        yaTiene(3L, hoy.minusDays(60), hoy.minusDays(1), Mandate.Status.active);
        yaTiene(4L, hoy.minusDays(90), null, Mandate.Status.ended);
        when(debts.countByMandateAndStatusIn(any(Mandate.class), anyCollection())).thenReturn(12L);

        MandatosResponse lista = servicio.listar(apofyx);

        assertTrue(lista.agencia());
        assertEquals(List.of("vigente", "por_empezar", "vencido", "terminado"),
                lista.mandatos().stream().map(MandatoPortalResponse::estado).toList());
        assertEquals(12L, lista.mandatos().getFirst().deudasEnGestion());
    }

    @Test
    void renovar_le_cambia_el_termino_y_un_vencido_vuelve_a_estar_vigente() {
        Mandate vencido = yaTiene(9L, LocalDate.of(2026, 1, 1), hoy.minusDays(1), Mandate.Status.active);

        MandatoPortalResponse renovado = servicio.renovar(apofyx, CAMILA, 9L,
                new RenovarMandatoRequest(hoy.plusMonths(6).toString()));

        assertEquals("vigente", renovado.estado());
        assertEquals(hoy.plusMonths(6), vencido.getValidTo());
        assertEquals(CAMILA, vencido.getRenewedBy());
        assertNotNull(vencido.getRenewedAt());

        servicio.renovar(apofyx, CAMILA, 9L, new RenovarMandatoRequest(null));
        assertNull(vencido.getValidTo(), "vacio: sin fecha de termino");
        assertEquals(HttpStatus.BAD_REQUEST, falla(() -> servicio.renovar(apofyx, CAMILA, 9L,
                new RenovarMandatoRequest(hoy.minusDays(1).toString())), "Esa fecha de término ya pasó: pon hoy o una posterior"));
    }

    @Test
    void terminar_lo_deja_terminado_con_quien_lo_hizo_y_ya_no_se_renueva_ni_se_termina_otra_vez() {
        Mandate mandato = yaTiene(9L, LocalDate.of(2026, 1, 1), null, Mandate.Status.active);

        MandatoPortalResponse terminado = servicio.terminar(apofyx, CAMILA, 9L);

        assertEquals("terminado", terminado.estado());
        assertEquals(Mandate.Status.ended, mandato.getStatus());
        assertEquals(CAMILA, mandato.getEndedBy());
        assertNotNull(mandato.getEndedAt());
        assertFalse(mandato.vigenteAl(hoy), "ya no se carga cartera ni se crean campanas con el");
        assertEquals(HttpStatus.CONFLICT, falla(() -> servicio.terminar(apofyx, CAMILA, 9L),
                "Ese mandato ya estaba terminado"));
        assertEquals(HttpStatus.CONFLICT, falla(() -> servicio.renovar(apofyx, CAMILA, 9L, new RenovarMandatoRequest(null)),
                "Ese mandato ya terminó: si vuelven a trabajar juntos, declara uno nuevo"));
    }

    @Test
    void el_mandato_de_otra_agencia_no_existe_para_ella() {
        when(mandates.findByIdAndAgency(77L, apofyx)).thenReturn(Optional.empty());

        assertEquals(HttpStatus.NOT_FOUND, falla(() -> servicio.terminar(apofyx, CAMILA, 77L),
                "Ese mandato no es de tu empresa"));
        assertEquals(HttpStatus.NOT_FOUND, falla(() -> servicio.renovar(apofyx, CAMILA, 77L, new RenovarMandatoRequest(null)),
                "Ese mandato no es de tu empresa"));
    }
}
