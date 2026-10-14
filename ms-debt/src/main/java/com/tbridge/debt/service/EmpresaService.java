package com.tbridge.debt.service;

import com.tbridge.debt.dto.request.NuevaEmpresaRequest;
import com.tbridge.debt.dto.response.AccionResponse;
import com.tbridge.debt.dto.response.EmpresaResponse;
import com.tbridge.debt.dto.response.OrganizacionResponse;
import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.model.AdminLog;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.Mandate;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.repository.AdminLogRepository;
import com.tbridge.debt.repository.ConteoDeDeudas;
import com.tbridge.debt.repository.DebtRepository;
import com.tbridge.debt.repository.MandateRepository;
import com.tbridge.debt.repository.OrganizationRepository;
import com.tbridge.debt.repository.SaldoPendiente;
import com.tbridge.debt.util.Rut;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Las empresas, para el admin de DataBridge: verlas y dar de alta a una.
 *
 * <p>Una empresa usuaria es una agencia de cobranza o un acreedor que cobra
 * directo. Antes solo existian las de los datos de ejemplo: no habia como
 * crear otra sin tocar la base. Los acreedores de una agencia no pasan por
 * aqui: los presenta ella con el mandato ({@link MandatoService}).
 *
 * <p>El admin ve <b>totales</b> de cada cartera, nunca lo que hay adentro. Por
 * eso los numeros salen de dos consultas que agrupan, y aqui no se lee ninguna
 * deuda ni ningun deudor.
 */
@Service
public class EmpresaService {

    private static final ZoneId CHILE = ZoneId.of("America/Santiago");

    private final OrganizationRepository organizations;
    private final MandateRepository mandates;
    private final DebtRepository debts;
    private final AdminLogRepository bitacora;

    public EmpresaService(OrganizationRepository organizations, MandateRepository mandates,
                          DebtRepository debts, AdminLogRepository bitacora) {
        this.organizations = organizations;
        this.mandates = mandates;
        this.debts = debts;
        this.bitacora = bitacora;
    }

    /** Todas las empresas, por nombre. */
    @Transactional(readOnly = true)
    public List<EmpresaResponse> listar() {
        Totales totales = totales();
        List<Mandate> mandatos = mandates.todosConSusEmpresas();
        return organizations.findAll(Sort.by(Sort.Order.asc("tradeName").ignoreCase())).stream()
                .map(empresa -> armar(empresa, totales, mandatos))
                .toList();
    }

    /** Una empresa, por su RUT. */
    @Transactional(readOnly = true)
    public EmpresaResponse ficha(String rutCrudo) {
        return armar(exigir(rutCrudo), totales(), mandates.todosConSusEmpresas());
    }

    /**
     * Da de alta una empresa usuaria. Queda anotado quien lo hizo.
     *
     * <p>Sus personas no se crean aqui: viven en ms-auth, y el panel las da de
     * alta alla una vez que la empresa existe.
     */
    @Transactional
    public EmpresaResponse crear(NuevaEmpresaRequest pedido, String admin) {
        String rut = Rut.normalizar(pedido.rut());
        if (!Rut.esValido(rut)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "El RUT no es valido: revisa el digito verificador");
        }
        organizations.findByRut(rut).ifPresent(existente -> {
            throw new ApiException(HttpStatus.CONFLICT, "Ya hay una empresa con ese RUT: "
                    + existente.getTradeName() + ". Abre su ficha para agregarle personas.");
        });

        String razonSocial = pedido.razonSocial().strip();
        String nombre = pedido.nombre() == null || pedido.nombre().isBlank() ? razonSocial : pedido.nombre().strip();
        Organization nueva = new Organization();
        nueva.setRut(rut);
        nueva.setLegalName(razonSocial);
        nueva.setTradeName(nombre.length() > 120 ? nombre.substring(0, 120) : nombre);
        nueva.setKind("agencia".equals(pedido.tipo()) ? Organization.Kind.agency : Organization.Kind.creditor);
        try {
            organizations.saveAndFlush(nueva);
        } catch (DataIntegrityViolationException repetida) {
            //  Dos altas del mismo RUT a la vez: la base deja pasar una sola.
            throw new ApiException(HttpStatus.CONFLICT, "Ya hay una empresa con ese RUT");
        }
        bitacora.save(AdminLog.de(admin, AdminLog.Action.empresa_creada, rut, nueva.getTradeName()));
        return armar(nueva, Totales.ninguno(), List.of());
    }

    /**
     * Suspende una empresa usuaria: sus personas no pueden entrar y sus claves
     * de API dejan de operar. Queda anotado quien lo hizo.
     */
    @Transactional
    public EmpresaResponse suspender(String rutCrudo, String admin) {
        Organization empresa = exigir(rutCrudo);
        if (empresa.getStatus() != Organization.Status.suspended) {
            empresa.setStatus(Organization.Status.suspended);
            organizations.save(empresa);
            bitacora.save(AdminLog.de(admin, AdminLog.Action.empresa_suspendida, empresa.getRut(), empresa.getTradeName()));
        }
        return armar(empresa, totales(), mandates.todosConSusEmpresas());
    }

    /**
     * Reactiva una empresa usuaria previamente suspendida. Queda anotado quien lo hizo.
     */
    @Transactional
    public EmpresaResponse reactivar(String rutCrudo, String admin) {
        Organization empresa = exigir(rutCrudo);
        if (empresa.getStatus() != Organization.Status.active) {
            empresa.setStatus(Organization.Status.active);
            organizations.save(empresa);
            bitacora.save(AdminLog.de(admin, AdminLog.Action.empresa_reactivada, empresa.getRut(), empresa.getTradeName()));
        }
        return armar(empresa, totales(), mandates.todosConSusEmpresas());
    }

    /** Lo que se hizo sobre una empresa desde el panel, lo mas reciente primero. */
    @Transactional(readOnly = true)
    public List<AccionResponse> historial(String rutCrudo) {
        return bitacora.findByOrgRutOrderByOccurredAtDesc(exigir(rutCrudo).getRut()).stream()
                .map(AccionResponse::from)
                .toList();
    }

    /** Si hay una empresa con ese RUT: lo que ms-auth pregunta antes de darle una persona. */
    @Transactional(readOnly = true)
    public Optional<OrganizacionResponse> buscar(String rutCrudo) {
        return organizations.findByRut(Rut.normalizar(rutCrudo)).map(OrganizacionResponse::from);
    }

    private Organization exigir(String rutCrudo) {
        return organizations.findByRut(Rut.normalizar(rutCrudo))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No hay ninguna empresa con ese RUT"));
    }

    // ------------------------------------------------------------------
    //  Los totales
    // ------------------------------------------------------------------

    /** Por empresa: cuantas deudas tiene y cuantas siguen en gestion, y lo que falta por cobrar en cada moneda. */
    private record Totales(Map<Long, long[]> deudas, Map<Long, Map<String, BigDecimal>> porCobrar) {

        /** Los de una empresa recien creada: todavia no hay nada a su nombre. */
        static Totales ninguno() {
            return new Totales(new HashMap<>(), new HashMap<>());
        }
    }

    private Totales totales() {
        Map<Long, long[]> deudas = new HashMap<>();
        for (ConteoDeDeudas conteo : debts.contarPorEmpresa()) {
            boolean enGestion = conteo.estado() != Debt.Status.paid && conteo.estado() != Debt.Status.withdrawn;
            for (Long empresa : deQuienes(conteo.acreedor(), conteo.emisor())) {
                long[] suyas = deudas.computeIfAbsent(empresa, id -> new long[2]);
                suyas[0] += conteo.deudas();
                if (enGestion) {
                    suyas[1] += conteo.deudas();
                }
            }
        }
        Map<Long, Map<String, BigDecimal>> porCobrar = new HashMap<>();
        for (SaldoPendiente saldo : debts.saldoPorEmpresa()) {
            for (Long empresa : deQuienes(saldo.acreedor(), saldo.emisor())) {
                porCobrar.computeIfAbsent(empresa, id -> new TreeMap<>())
                        .merge(saldo.moneda().name(), saldo.monto(), BigDecimal::add);
            }
        }
        return new Totales(deudas, porCobrar);
    }

    /**
     * Una deuda es de la cartera de su acreedor y de la de quien la entrego:
     * la misma regla con que cada empresa ve la suya en su portal
     * ({@link DebtRepository#carteraDe}). Si el acreedor la entrego el mismo,
     * cuenta una vez.
     */
    private static Set<Long> deQuienes(Long acreedor, Long emisor) {
        return acreedor.equals(emisor) ? Set.of(acreedor) : Set.of(acreedor, emisor);
    }

    private static EmpresaResponse armar(Organization empresa, Totales totales, List<Mandate> mandatos) {
        long[] deudas = totales.deudas().getOrDefault(empresa.getId(), new long[2]);
        List<EmpresaResponse.Monto> porCobrar = totales.porCobrar().getOrDefault(empresa.getId(), Map.of())
                .entrySet().stream()
                .map(total -> new EmpresaResponse.Monto(total.getKey(), total.getValue()))
                .toList();
        return new EmpresaResponse(empresa.getRut(), empresa.getLegalName(), empresa.getTradeName(),
                tipo(empresa), empresa.getStatus() == Organization.Status.active, empresa.getCreatedAt(),
                deudas[0], deudas[1], porCobrar, mandatosDe(empresa, mandatos));
    }

    private static List<EmpresaResponse.Mandato> mandatosDe(Organization empresa, List<Mandate> todos) {
        LocalDate hoy = LocalDate.now(CHILE);
        List<EmpresaResponse.Mandato> suyos = new ArrayList<>();
        for (Mandate mandato : todos) {
            if (mandato.getAgency().getId().equals(empresa.getId())) {
                suyos.add(mandato("cobra_para", mandato.getCreditor(), mandato, hoy));
            } else if (mandato.getCreditor().getId().equals(empresa.getId())) {
                suyos.add(mandato("le_cobra", mandato.getAgency(), mandato, hoy));
            }
        }
        return suyos;
    }

    private static EmpresaResponse.Mandato mandato(String papel, Organization otra, Mandate mandato, LocalDate hoy) {
        return new EmpresaResponse.Mandato(papel, otra.getRut(), otra.getTradeName(),
                mandato.getValidFrom(), mandato.getValidTo(), mandato.vigenteAl(hoy));
    }

    private static String tipo(Organization empresa) {
        return switch (empresa.getKind()) {
            case creditor -> "acreedor";
            case agency -> "agencia";
            case both -> "ambos";
        };
    }
}
