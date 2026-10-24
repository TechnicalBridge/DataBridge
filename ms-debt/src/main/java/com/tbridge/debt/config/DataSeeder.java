package com.tbridge.debt.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.tbridge.debt.dto.request.CampanaRequest;
import com.tbridge.debt.dto.request.DisputaRequest;
import com.tbridge.debt.events.PagoConfirmado;
import com.tbridge.debt.model.Batch;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.Installment;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.DebtRepository;
import com.tbridge.debt.repository.DebtorRepository;
import com.tbridge.debt.repository.InstallmentRepository;
import com.tbridge.debt.repository.OrganizationRepository;
import com.tbridge.debt.security.JwtPrincipal;
import com.tbridge.debt.service.CarteraIntakeService;
import com.tbridge.debt.service.DebtService;
import com.tbridge.debt.service.DisputaService;
import com.tbridge.debt.service.MandatoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

/**
 * Una demo de DataBridge solo: la empresa cobra lo suyo por campana, sin
 * agencias ni mandatos. Las deudas pasan por la misma ingesta que el portal;
 * los convenios, pagos simulados y reclamos usan los servicios del producto.
 * Solo se instala, por opcion expresa, en una base sin datos de negocio.
 */
@Component
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);
    private static final ZoneId CHILE = ZoneId.of("America/Santiago");
    static final String EMPRESA_RUT = "76000000-0";
    static final String CAMPANA_CLP = "DEMO-PESOS";
    static final String CAMPANA_UF = "DEMO-UF";

    private final OrganizationRepository organizaciones;
    private final DebtorRepository deudores;
    private final DebtRepository deudas;
    private final InstallmentRepository cuotas;
    private final MandatoService campanas;
    private final CarteraIntakeService ingesta;
    private final DebtService cartera;
    private final DisputaService disputas;
    private final ObjectMapper json;
    private final boolean habilitada;

    public DataSeeder(OrganizationRepository organizaciones, DebtorRepository deudores, DebtRepository deudas,
                      InstallmentRepository cuotas, MandatoService campanas, CarteraIntakeService ingesta,
                      DebtService cartera, DisputaService disputas, ObjectMapper json,
                      @Value("${app.demo.datos:false}") boolean habilitada) {
        this.organizaciones = organizaciones;
        this.deudores = deudores;
        this.deudas = deudas;
        this.cuotas = cuotas;
        this.campanas = campanas;
        this.ingesta = ingesta;
        this.cartera = cartera;
        this.disputas = disputas;
        this.json = json;
        this.habilitada = habilitada;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (!habilitada) {
            return;
        }
        if (organizaciones.count() > 0 || deudores.count() > 0 || deudas.count() > 0) {
            log.info("La base ya tiene datos de negocio: no se instala ni se modifica la demo");
            return;
        }
        LocalDate hoy = LocalDate.now(CHILE);
        Organization empresa = new Organization();
        empresa.setRut(EMPRESA_RUT);
        empresa.setLegalName("Servicios Horizonte SpA");
        empresa.setTradeName("Servicios Horizonte");
        empresa.setKind(Organization.Kind.creditor);
        organizaciones.save(empresa);

        List<Ejemplo> pesos = List.of(
                new Ejemplo("DEMO-CONVENIO", "16482337-7", "Felipe Rojas Muñoz", "felipe.rojas@example.com", "520000", false),
                new Ejemplo("DEMO-PAGADA", "19230418-0", "Carolina Muñoz Vera", "carolina.munoz@example.com", "380000", false),
                new Ejemplo("DEMO-RECLAMO", "14583206-3", "Rodrigo Pérez Contreras", "rodrigo.perez@example.com", "450000", false),
                new Ejemplo("DEMO-RIESGO", "17893456-2", "Ignacio Tapia Rojas", "ignacio.tapia@example.com", "350000", false));
        List<Ejemplo> uf = List.of(
                new Ejemplo("DEMO-UF-ABIERTA", "76991245-2", "Comercial Ñandú SpA", "administracion.nandu@example.com", "38.5", true),
                new Ejemplo("DEMO-UF-CONVENIO", "76284519-9", "Panadería La Espiga Ltda.", "contacto.laespiga@example.com", "24", true));
        cargar(empresa, CAMPANA_CLP, "Cartera en pesos", pesos, "CLP", hoy);
        cargar(empresa, CAMPANA_UF, "Cartera en UF", uf, "UF", hoy);

        Debt felipe = buscar(empresa, "DEMO-CONVENIO");
        cartera.applyRepact(deudor(felipe), felipe.getId(), 6);
        pagar(felipe, pendientes(felipe).subList(0, 3));
        Debt carolina = buscar(empresa, "DEMO-PAGADA");
        pagar(carolina, pendientes(carolina));
        Debt rodrigo = buscar(empresa, "DEMO-RECLAMO");
        disputas.disputar(deudor(rodrigo), rodrigo.getId(),
                new DisputaRequest("monto_incorrecto", "Solicito revisar el monto de esta deuda de ejemplo."));
        Debt ignacio = buscar(empresa, "DEMO-RIESGO");
        cartera.applyRepact(deudor(ignacio), ignacio.getId(), 6);
        // La foto de demo incluye un convenio que ya incumplio su primera cuota.
        Installment vencida = pendientes(ignacio).getFirst();
        vencida.setDueDate(hoy.minusDays(10));
        cuotas.save(vencida);
        Debt espiga = buscar(empresa, "DEMO-UF-CONVENIO");
        cartera.applyRepact(deudor(espiga), espiga.getId(), 3);
        pagar(espiga, pendientes(espiga).subList(0, 1));
        log.info("Demo instalada: una empresa acreedora, dos campanas y seis deudas de ejemplo");
    }

    private record Ejemplo(String id, String rut, String nombre, String correo, String mensual, boolean personaJuridica) {}

    private void cargar(Organization empresa, String id, String nombre, List<Ejemplo> ejemplos,
                         String moneda, LocalDate hoy) {
        campanas.registrarCampana(empresa, new CampanaRequest(id, EMPRESA_RUT, nombre,
                hoy.toString(), null, json.createArrayNode().add("correo"), 3,
                json.createArrayNode().add(1).add(4).add(11), "en_curso"));
        ObjectNode payload = json.createObjectNode();
        payload.put("version", "1.0");
        ObjectNode lote = payload.putObject("lote");
        lote.put("id_externo", id + "-INICIAL");
        lote.put("fecha_corte", hoy.toString());
        lote.putObject("acreedor").put("rut", EMPRESA_RUT);
        lote.put("campana_id_externo", id);
        var lista = payload.putArray("deudas");
        for (Ejemplo ejemplo : ejemplos) {
            ObjectNode deuda = lista.addObject();
            deuda.put("id_externo", ejemplo.id());
            deuda.put("moneda", moneda);
            deuda.put("concepto", "Servicios mensuales");
            ObjectNode persona = deuda.putObject("deudor");
            persona.put("rut", ejemplo.rut());
            persona.put("tipo", ejemplo.personaJuridica() ? "empresa" : "persona");
            persona.put("nombre", ejemplo.nombre());
            persona.put("correo", ejemplo.correo());
            var cargos = deuda.putArray("cargos");
            for (int meses = 2; meses >= 1; meses--) {
                LocalDate vence = hoy.withDayOfMonth(1).minusMonths(meses).withDayOfMonth(5);
                ObjectNode cargo = cargos.addObject();
                cargo.put("concepto", "Servicios " + vence.toString().substring(0, 7));
                cargo.put("periodo", vence.toString().substring(0, 7));
                cargo.put("monto", new BigDecimal(ejemplo.mensual()));
                cargo.put("fecha_vencimiento", vence.toString());
            }
        }
        var resultado = ingesta.recibir(empresa, payload, Batch.Source.file);
        if (resultado.rechazadas() != 0 || resultado.aceptadas() != ejemplos.size()) {
            throw new IllegalStateException("La ingesta rechazo datos de la demo: " + resultado.resultados());
        }
    }

    private Debt buscar(Organization empresa, String id) {
        return deudas.findByCreditorAndExternalId(empresa, id).orElseThrow();
    }

    private List<Installment> pendientes(Debt deuda) {
        return cuotas.findByDebtOrderByNumberAsc(deuda).stream()
                .filter(c -> c.getStatus() == Installment.Status.pending).toList();
    }

    /** Simula solo la aplicacion local: nunca crea un cobro en una pasarela. */
    private void pagar(Debt deuda, List<Installment> pagadas) {
        BigDecimal monto = pagadas.stream().map(Installment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal valorUf = deuda.getCurrency() == Debt.Currency.UF ? new BigDecimal("39876.54") : null;
        long pesos = (valorUf == null ? monto : monto.multiply(valorUf)).setScale(0, RoundingMode.HALF_UP).longValueExact();
        cartera.onPagoConfirmado(new PagoConfirmado(PagoConfirmado.TIPO, null, deuda.getId(), null,
                deuda.getDebtor().getRut(), EMPRESA_RUT, monto, deuda.getCurrency().name(), pesos, valorUf,
                "demo", deuda.getExternalId(), Instant.now(), pagadas.stream().map(Installment::getId).toList(), null));
    }

    private static JwtPrincipal deudor(Debt deuda) {
        String rut = deuda.getDebtor().getRut();
        return new JwtPrincipal(rut, null, "DEBTOR", deuda.getDebtor().getFullName(), rut);
    }
}
