package com.tbridge.debt.service;

import com.tbridge.debt.dto.request.CampanaRequest;
import com.tbridge.debt.dto.request.MandatoRequest;
import com.tbridge.debt.exception.CarteraInvalida;
import com.tbridge.debt.model.Campaign;
import com.tbridge.debt.model.Mandate;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.CampaignRepository;
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

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Una empresa nueva de la agencia llega a DataBridge con el mandato: nadie la
 * tiene que cargar a mano antes.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MandatoServiceTest {

    @Mock private OrganizationRepository organizations;
    @Mock private MandateRepository mandates;
    @Mock private CampaignRepository campaigns;

    private MandatoService servicio;
    private Organization apofyx;

    @BeforeEach
    void preparar() {
        servicio = new MandatoService(organizations, mandates, campaigns,
                new DescuentoService(mandates, new com.fasterxml.jackson.databind.ObjectMapper()));
        apofyx = new Organization();
        apofyx.setId(1L);
        apofyx.setRut("77305118-6");
        apofyx.setTradeName("APOFYX");
        apofyx.setKind(Organization.Kind.agency);

        when(organizations.findByRut(any())).thenReturn(Optional.empty());
        when(organizations.save(any(Organization.class))).thenAnswer(llamada -> {
            Organization guardada = llamada.getArgument(0);
            guardada.setId(2L);
            return guardada;
        });
        when(mandates.save(any(Mandate.class))).thenAnswer(llamada -> llamada.getArgument(0));
    }

    private static MandatoRequest mandato(String rut, String razonSocial, String nombre) {
        return new MandatoRequest(rut, razonSocial, nombre, "2026-09-01", null, 120);
    }

    @Test
    void un_acreedor_que_databridge_no_conoce_se_registra_con_el_mandato() {
        servicio.registrarMandato(apofyx, mandato("76.418.902-7", "Patrimonio Inmuebles SpA", "Patrimonio Inmuebles"));

        ArgumentCaptor<Organization> nueva = ArgumentCaptor.forClass(Organization.class);
        verify(organizations).save(nueva.capture());
        assertEquals("76418902-7", nueva.getValue().getRut());
        assertEquals("Patrimonio Inmuebles SpA", nueva.getValue().getLegalName());
        assertEquals("Patrimonio Inmuebles", nueva.getValue().getTradeName());
        assertEquals(Organization.Kind.creditor, nueva.getValue().getKind());
    }

    @Test
    void sin_nombres_el_acreedor_queda_con_su_rut() {
        servicio.registrarMandato(apofyx, mandato("76418902-7", null, " "));

        ArgumentCaptor<Organization> nueva = ArgumentCaptor.forClass(Organization.class);
        verify(organizations).save(nueva.capture());
        assertEquals("76418902-7", nueva.getValue().getLegalName());
        assertEquals("76418902-7", nueva.getValue().getTradeName());
    }

    @Test
    void un_acreedor_que_ya_existe_no_se_toca() {
        Organization patrimonio = new Organization();
        patrimonio.setId(2L);
        patrimonio.setRut("76418902-7");
        patrimonio.setLegalName("Patrimonio Inmuebles SpA");
        patrimonio.setTradeName("Patrimonio Inmuebles");
        when(organizations.findByRut("76418902-7")).thenReturn(Optional.of(patrimonio));

        servicio.registrarMandato(apofyx, mandato("76418902-7", "Otro nombre SpA", "Otro"));

        verify(organizations, never()).save(any());
        assertEquals("Patrimonio Inmuebles", patrimonio.getTradeName());
    }

    @Test
    void un_rut_invalido_no_registra_a_nadie() {
        CarteraInvalida fallo = assertThrows(CarteraInvalida.class,
                () -> servicio.registrarMandato(apofyx, mandato("76418902-0", "Mal escrito SpA", null)));

        assertEquals("acreedor_invalido", fallo.getCodigo());
        verify(organizations, never()).save(any());
    }

    @Test
    void una_campana_sin_mandato_previo_sigue_sin_acreedor() {
        CampanaRequest campana = new CampanaRequest("APX-CMP-9", "76418902-7", "Arriendos", "2026-09-19",
                null, null, null, null, null);

        CarteraInvalida fallo = assertThrows(CarteraInvalida.class,
                () -> servicio.registrarCampana(apofyx, campana));

        assertEquals("acreedor_desconocido", fallo.getCodigo());
        assertEquals(404, fallo.getStatus());
    }

    // ------------------------------------------------------------------
    //  El estado de la campana, que decide la agencia
    // ------------------------------------------------------------------

    private Campaign campanaRegistrada() {
        Organization patrimonio = new Organization();
        patrimonio.setId(2L);
        patrimonio.setRut("76418902-7");
        when(organizations.findByRut("76418902-7")).thenReturn(Optional.of(patrimonio));
        when(mandates.findByAgencyAndCreditorAndStatus(apofyx, patrimonio, Mandate.Status.active))
                .thenReturn(java.util.List.of(new Mandate()));
        Campaign existente = new Campaign();
        existente.setId(10L);
        when(campaigns.findByAgencyAndExternalId(apofyx, "APX-CMP-9")).thenReturn(Optional.of(existente));
        return existente;
    }

    private static CampanaRequest conEstado(String estado) {
        return new CampanaRequest("APX-CMP-9", "76418902-7", "Arriendos", "2026-09-19", null, null, null, null,
                estado);
    }

    @Test
    void la_agencia_pausa_y_termina_su_campana() {
        Campaign campana = campanaRegistrada();

        servicio.registrarCampana(apofyx, conEstado("pausada"));
        assertEquals(Campaign.Status.paused, campana.getStatus());

        servicio.registrarCampana(apofyx, conEstado("terminada"));
        assertEquals(Campaign.Status.finished, campana.getStatus());

        CarteraInvalida fallo = assertThrows(CarteraInvalida.class,
                () -> servicio.registrarCampana(apofyx, conEstado("en_curso")));
        assertEquals("campana_terminada", fallo.getCodigo());
        assertEquals(409, fallo.getStatus());
        assertEquals(Campaign.Status.finished, campana.getStatus());
    }

    @Test
    void una_campana_terminada_no_se_puede_pausar() {
        Campaign campana = campanaRegistrada();
        servicio.registrarCampana(apofyx, conEstado("terminada"));

        CarteraInvalida fallo = assertThrows(CarteraInvalida.class,
                () -> servicio.registrarCampana(apofyx, conEstado("pausada")));
        assertEquals("campana_terminada", fallo.getCodigo());
        assertEquals(409, fallo.getStatus());
    }

    @Test
    void mandar_terminada_de_nuevo_es_idempotente() {
        Campaign campana = campanaRegistrada();
        servicio.registrarCampana(apofyx, conEstado("terminada"));

        servicio.registrarCampana(apofyx, conEstado("terminada"));
        assertEquals(Campaign.Status.finished, campana.getStatus());
    }

    @Test
    void sin_estado_la_campana_no_cambia() {
        Campaign campana = campanaRegistrada();
        campana.setStatus(Campaign.Status.paused);

        servicio.registrarCampana(apofyx, conEstado(null));

        assertEquals(Campaign.Status.paused, campana.getStatus());
    }

    @Test
    void un_estado_que_no_existe_se_rechaza() {
        campanaRegistrada();

        CarteraInvalida fallo = assertThrows(CarteraInvalida.class,
                () -> servicio.registrarCampana(apofyx, conEstado("borrador")));

        assertEquals("estado_invalido", fallo.getCodigo());
    }

    // ------------------------------------------------------------------
    //  El acreedor que cobra sin agencia, con sus propias campanas
    // ------------------------------------------------------------------

    private Organization acreedor(long id, String rut) {
        Organization acreedor = new Organization();
        acreedor.setId(id);
        acreedor.setRut(rut);
        acreedor.setKind(Organization.Kind.creditor);
        when(organizations.findByRut(rut)).thenReturn(Optional.of(acreedor));
        return acreedor;
    }

    @Test
    void un_acreedor_registra_su_propia_campana_sin_mandato() {
        Organization andes = acreedor(5L, "76543210-3");
        when(campaigns.findByAgencyAndExternalId(andes, "AND-CMP-1")).thenReturn(Optional.empty());
        when(campaigns.save(any(Campaign.class))).thenAnswer(llamada -> llamada.getArgument(0));

        servicio.registrarCampana(andes, new CampanaRequest("AND-CMP-1", "76543210-3", "Aranceles", "2026-10-01",
                null, null, 3, null, "en_curso"));

        ArgumentCaptor<Campaign> guardada = ArgumentCaptor.forClass(Campaign.class);
        verify(campaigns).save(guardada.capture());
        assertEquals(andes, guardada.getValue().getAgency(), "la gestiona el mismo acreedor");
        assertEquals(andes, guardada.getValue().getCreditor());
        assertEquals(Campaign.Status.running, guardada.getValue().getStatus());
        verify(mandates, never()).findByAgencyAndCreditorAndStatus(any(), any(), any());
    }

    @Test
    void el_acreedor_pausa_y_termina_la_suya() {
        Organization andes = acreedor(5L, "76543210-3");
        Campaign suya = new Campaign();
        when(campaigns.findByAgencyAndExternalId(andes, "AND-CMP-1")).thenReturn(Optional.of(suya));

        servicio.registrarCampana(andes, new CampanaRequest("AND-CMP-1", "76543210-3", null, null, null, null, null,
                null, "pausada"));
        assertEquals(Campaign.Status.paused, suya.getStatus());

        servicio.registrarCampana(andes, new CampanaRequest("AND-CMP-1", "76543210-3", null, null, null, null, null,
                null, "terminada"));
        assertEquals(Campaign.Status.finished, suya.getStatus());
    }

    @Test
    void un_acreedor_no_registra_la_campana_de_otro() {
        Organization andes = acreedor(5L, "76543210-3");
        acreedor(6L, "76418902-7");

        CarteraInvalida fallo = assertThrows(CarteraInvalida.class,
                () -> servicio.registrarCampana(andes, new CampanaRequest("AND-CMP-2", "76418902-7", "Ajena", null,
                        null, null, null, null, null)));

        assertEquals("sin_mandato", fallo.getCodigo());
        assertEquals(403, fallo.getStatus());
        verify(campaigns, never()).save(any());
    }

    // ------------------------------------------------------------------
    //  Lo que no viene no cambia, y lo que viene se valida
    // ------------------------------------------------------------------

    private static CampanaRequest pedido(String nombre, String inicio, String fin, Integer intentos, String cadencia,
                                         String estado) throws Exception {
        return new CampanaRequest("APX-CMP-9", "76418902-7", nombre, inicio, fin, null, intentos,
                cadencia == null ? null : new com.fasterxml.jackson.databind.ObjectMapper().readTree(cadencia), estado);
    }

    @Test
    void mandar_solo_el_estado_no_pisa_el_nombre_ni_el_inicio() throws Exception {
        Campaign campana = campanaRegistrada();
        campana.setId(7L);
        campana.setName("Arriendos octubre");
        campana.setStartsOn(java.time.LocalDate.of(2026, 10, 1));

        servicio.registrarCampana(apofyx, pedido(null, null, null, null, null, "pausada"));

        assertEquals("Arriendos octubre", campana.getName());
        assertEquals(java.time.LocalDate.of(2026, 10, 1), campana.getStartsOn());
        assertEquals(Campaign.Status.paused, campana.getStatus());
    }

    @Test
    void una_cadencia_que_no_crece_o_con_ceros_se_rechaza() throws Exception {
        campanaRegistrada();
        for (String mala : new String[]{"[1, 1, 4]", "[0, 3]", "[4, 2]", "[1.5]", "[\"uno\"]"}) {
            CarteraInvalida fallo = assertThrows(CarteraInvalida.class,
                    () -> servicio.registrarCampana(apofyx, pedido("A", "2026-10-01", null, null, mala, null)), mala);
            assertEquals("cadencia_invalida", fallo.getCodigo(), mala);
        }
        servicio.registrarCampana(apofyx, pedido("A", "2026-10-01", null, null, "[1, 4, 11]", null));
    }

    @Test
    void los_intentos_van_de_1_a_10() throws Exception {
        campanaRegistrada();
        for (int malos : new int[]{0, 11}) {
            CarteraInvalida fallo = assertThrows(CarteraInvalida.class,
                    () -> servicio.registrarCampana(apofyx, pedido("A", "2026-10-01", null, malos, null, null)));
            assertEquals("intentos_invalidos", fallo.getCodigo());
        }
    }

    @Test
    void un_id_externo_de_mas_de_64_caracteres_se_rechaza() throws Exception {
        campanaRegistrada();
        CampanaRequest largo = new CampanaRequest("C".repeat(65), "76418902-7", "A", null, null, null, null, null, null);

        CarteraInvalida fallo = assertThrows(CarteraInvalida.class, () -> servicio.registrarCampana(apofyx, largo));

        assertEquals("id_invalido", fallo.getCodigo());
    }

    @Test
    void una_campana_no_termina_antes_de_empezar() throws Exception {
        campanaRegistrada();

        CarteraInvalida fallo = assertThrows(CarteraInvalida.class,
                () -> servicio.registrarCampana(apofyx, pedido("A", "2026-10-10", "2026-10-01", null, null, null)));

        assertEquals("fechas_invalidas", fallo.getCodigo());
    }

    // ------------------------------------------------------------------
    //  El descuento por pronto pago (contrato §7.1)
    // ------------------------------------------------------------------

    private static MandatoRequest conDescuento(String maximo) {
        return new MandatoRequest("76418902-7", "Patrimonio Inmuebles SpA", "Patrimonio Inmuebles", "2026-09-01",
                null, 120, maximo == null ? null : new BigDecimal(maximo));
    }

    private static CampanaRequest ofrece(String porTramo) throws Exception {
        return new CampanaRequest("APX-CMP-9", "76418902-7", null, null, null, null, null, null, null,
                new com.fasterxml.jackson.databind.ObjectMapper().readTree(porTramo));
    }

    /** La campana de APOFYX sobre Patrimonio, con un mandato vigente que autoriza este maximo. */
    private Campaign campanaConMaximo(String maximo) {
        Campaign campana = campanaRegistrada();
        Mandate mandato = new Mandate();
        mandato.setValidFrom(LocalDate.of(2026, 9, 1));
        mandato.setMaxMoraDiscount(maximo == null ? null : new BigDecimal(maximo));
        when(mandates.findByAgencyAndCreditorAndStatus(eq(apofyx), any(), eq(Mandate.Status.active)))
                .thenReturn(List.of(mandato));
        return campana;
    }

    @Test
    void el_mandato_guarda_el_maximo_que_autoriza_el_acreedor() {
        ArgumentCaptor<Mandate> guardado = ArgumentCaptor.forClass(Mandate.class);

        servicio.registrarMandato(apofyx, conDescuento("100"));

        verify(mandates).save(guardado.capture());
        assertEquals(new BigDecimal("100"), guardado.getValue().getMaxMoraDiscount());
    }

    @Test
    void reenviar_el_mandato_cambia_el_maximo() {
        Organization patrimonio = new Organization();
        patrimonio.setRut("76418902-7");
        Mandate existente = new Mandate();
        existente.setCreditor(patrimonio);
        existente.setValidFrom(LocalDate.of(2026, 9, 1));
        existente.setMaxMoraDiscount(new BigDecimal("100"));
        when(mandates.findByAgencyAndCreditorAndStatus(eq(apofyx), any(), eq(Mandate.Status.active)))
                .thenReturn(List.of(existente));

        servicio.registrarMandato(apofyx, conDescuento("50"));

        assertEquals(new BigDecimal("50"), existente.getMaxMoraDiscount());
    }

    @Test
    void un_maximo_fuera_de_0_a_100_se_rechaza() {
        for (String malo : new String[]{"-1", "100.01", "12.345"}) {
            CarteraInvalida fallo = assertThrows(CarteraInvalida.class,
                    () -> servicio.registrarMandato(apofyx, conDescuento(malo)));
            assertEquals("descuento_invalido", fallo.getCodigo(), malo);
        }
    }

    @Test
    void la_campana_guarda_su_descuento_por_tramo() throws Exception {
        Campaign campana = campanaConMaximo("100");

        servicio.registrarCampana(apofyx, ofrece("{\"91-120\": 100, \"1-30\": 0, \"31-90\": 50}"));

        assertEquals("{\"1-30\":0,\"31-90\":50,\"91-120\":100}", campana.getMoraDiscount());
    }

    @Test
    void un_tramo_sobre_el_maximo_del_acreedor_se_rechaza() throws Exception {
        campanaConMaximo("50");

        CarteraInvalida fallo = assertThrows(CarteraInvalida.class,
                () -> servicio.registrarCampana(apofyx, ofrece("{\"31-90\": 50, \"91-120\": 100}")));

        assertEquals("descuento_sobre_tope", fallo.getCodigo());
    }

    @Test
    void sin_maximo_autorizado_ninguna_campana_ofrece_descuento() throws Exception {
        campanaConMaximo(null);

        CarteraInvalida fallo = assertThrows(CarteraInvalida.class,
                () -> servicio.registrarCampana(apofyx, ofrece("{\"91-120\": 10}")));

        assertEquals("descuento_sobre_tope", fallo.getCodigo());
    }

    @Test
    void un_tramo_que_no_existe_o_un_valor_que_no_es_porcentaje_se_rechaza() throws Exception {
        campanaConMaximo("100");
        for (String malo : new String[]{"{\"121-200\": 10}", "{\"31-90\": 150}", "{\"31-90\": \"50\"}", "[50]"}) {
            CarteraInvalida fallo = assertThrows(CarteraInvalida.class,
                    () -> servicio.registrarCampana(apofyx, ofrece(malo)));
            assertEquals("descuento_invalido", fallo.getCodigo(), malo);
        }
    }

    @Test
    void sin_el_campo_no_cambia_y_vacio_lo_quita() throws Exception {
        Campaign campana = campanaConMaximo("100");
        campana.setMoraDiscount("{\"31-90\":50}");

        servicio.registrarCampana(apofyx, conEstado("pausada"));
        assertEquals("{\"31-90\":50}", campana.getMoraDiscount());

        servicio.registrarCampana(apofyx, ofrece("{}"));
        assertNull(campana.getMoraDiscount());
    }

    @Test
    void el_acreedor_sin_agencia_autoriza_su_propio_descuento() throws Exception {
        Organization andes = new Organization();
        andes.setId(3L);
        andes.setRut("76543210-3");
        when(organizations.findByRut("76543210-3")).thenReturn(Optional.of(andes));
        Campaign propia = new Campaign();
        when(campaigns.findByAgencyAndExternalId(andes, "AND-CMP-1")).thenReturn(Optional.of(propia));

        servicio.registrarCampana(andes, new CampanaRequest("AND-CMP-1", "76543210-3", null, null, null, null, null,
                null, null, new com.fasterxml.jackson.databind.ObjectMapper().readTree("{\"91-120\": 100}")));

        assertEquals("{\"91-120\":100}", propia.getMoraDiscount());
    }
}
