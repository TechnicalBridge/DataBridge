package com.tbridge.debt.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.debt.events.PagoConfirmado;
import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.security.JwtPrincipal;
import com.tbridge.debt.dto.evento.DeudaSaldadaDatos;
import com.tbridge.debt.dto.evento.PagoConfirmadoDatos;
import com.tbridge.debt.dto.evento.RepactacionAceptadaDatos;
import com.tbridge.debt.dto.response.DebtDetailResponse;
import com.tbridge.debt.util.Rut;
import com.tbridge.debt.dto.response.DebtSnapshotResponse;
import com.tbridge.debt.dto.response.DebtSummaryResponse;
import com.tbridge.debt.dto.response.InstallmentPreview;
import com.tbridge.debt.dto.response.RepactPlan;
import com.tbridge.debt.model.Campaign;
import com.tbridge.debt.model.Contact;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.DebtEvent;
import com.tbridge.debt.model.DetallePago;
import com.tbridge.debt.model.Debtor;
import com.tbridge.debt.model.Installment;
import com.tbridge.debt.model.Organization;
import com.tbridge.debt.model.Repactation;
import com.tbridge.debt.repository.ContactRepository;
import com.tbridge.debt.repository.DebtChargeRepository;
import com.tbridge.debt.repository.DebtEventRepository;
import com.tbridge.debt.repository.DebtRepository;
import com.tbridge.debt.repository.DebtorRepository;
import com.tbridge.debt.repository.InstallmentRepository;
import com.tbridge.debt.repository.OrganizationRepository;
import com.tbridge.debt.repository.RepactationRepository;
import com.tbridge.debt.repository.UltimoContacto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Las deudas.
 *
 * <p><b>Toda consulta de una empresa pasa por su organizacion.</b> Antes esto
 * era {@code findAll()} para cualquiera con rol CREDITOR, asi que todos veian
 * las carteras de todos.
 *
 * <p><b>El saldo se calcula.</b> Sale de las cuotas pendientes, nunca de una
 * columna guardada: un total almacenado al lado de sus partes termina
 * discrepando de ellas.
 */
@Service
//  open-in-view esta apagado a proposito: la sesion de Hibernate no sigue
//  abierta mientras se arma la respuesta. Por eso las lecturas tambien van en
//  una transaccion, o las relaciones perezosas revientan al tocarlas.
@Transactional(readOnly = true)
public class DebtService {

    private static final Logger log = LoggerFactory.getLogger(DebtService.class);
    private static final ZoneId CHILE = ZoneId.of("America/Santiago");
    private static final BigDecimal CIEN = BigDecimal.valueOf(100);

    /** El orden en que se pagan las cuotas: la que vence primero, primero. */
    static final Comparator<Installment> EN_ORDEN =
            Comparator.comparing(Installment::getDueDate).thenComparing(Installment::getNumber);

    private final DebtRepository debts;
    private final DebtorRepository debtors;
    private final OrganizationRepository organizations;
    private final DebtChargeRepository charges;
    private final InstallmentRepository installments;
    private final RepactationRepository repactations;
    private final DebtEventRepository events;
    private final ContactRepository contactos;
    private final RepactationService repactation;
    private final EventosService eventos;
    private final ObjectMapper json;
    private final DescuentoService descuentos;

    public DebtService(
            DebtRepository debts,
            DebtorRepository debtors,
            OrganizationRepository organizations,
            DebtChargeRepository charges,
            InstallmentRepository installments,
            RepactationRepository repactations,
            DebtEventRepository events,
            ContactRepository contactos,
            RepactationService repactation,
            EventosService eventos,
            ObjectMapper json,
            DescuentoService descuentos
    ) {
        this.debts = debts;
        this.debtors = debtors;
        this.organizations = organizations;
        this.charges = charges;
        this.installments = installments;
        this.repactations = repactations;
        this.events = events;
        this.contactos = contactos;
        this.repactation = repactation;
        this.eventos = eventos;
        this.json = json;
        this.descuentos = descuentos;
    }

    // ------------------------------------------------------------------
    //  Quien esta preguntando
    // ------------------------------------------------------------------

    /**
     * La organizacion detras de la sesion de una empresa.
     *
     * <p>Se resuelve por RUT. Sin RUT en el token no hay forma de acotar la
     * cartera, y devolver "todas" seria repetir la fuga que este rediseno vino
     * a cerrar.
     */
    public Organization organizacionDe(JwtPrincipal user) {
        if (user == null || user.rut() == null || user.rut().isBlank()) {
            throw new ApiException(HttpStatus.FORBIDDEN,
                    "El token no dice de que empresa eres: no se puede mostrar una cartera");
        }
        return organizations.findByRut(user.rut())
                .orElseThrow(() -> new ApiException(HttpStatus.FORBIDDEN,
                        "Esa empresa no esta registrada en DataBridge"));
    }

    /**
     * Las deudas de la cartera de una empresa, para ms-payments: las mismas que
     * ve en el portal, la suya como acreedora y la que entrego como agencia.
     * Vacia si la empresa no esta registrada.
     */
    public List<Long> carteraInterna(String rut) {
        return organizations.findByRut(rut)
                .map(org -> debts.carteraDe(org).stream().map(Debt::getId).toList())
                .orElse(List.of());
    }

    /**
     * El correo que registro el acreedor para ese RUT, para ms-auth: el enlace
     * de acceso va solo ahi, nunca al que alguien escriba en el formulario.
     */
    public java.util.Optional<String> correoDelDeudor(String rut) {
        String normalizado = Rut.normalizar(rut);
        if (!Rut.esValido(normalizado)) {
            return java.util.Optional.empty();
        }
        return debtors.findByRut(normalizado).map(Debtor::getEmail).filter(c -> c != null && c.contains("@"));
    }

    /** El deudor detras del token, por RUT: es lo unico que trae su sesion. */
    Debtor deudorDe(JwtPrincipal user) {
        if (user == null || user.rut() == null || user.rut().isBlank()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "La sesion no identifica al deudor");
        }
        return debtors.findByRut(user.rut())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "No hay deudas a tu nombre"));
    }

    // ------------------------------------------------------------------
    //  Consultas
    // ------------------------------------------------------------------

    /**
     * Lo que ve cada quien: la empresa, su cartera; el deudor, lo suyo. Cuando
     * el que mira es el deudor, se anota que entro (ver {@link #anotarIngreso}).
     */
    @Transactional
    public List<DebtSummaryResponse> listFor(JwtPrincipal user) {
        if (user != null && user.isCreditor()) {
            List<Debt> cartera = debts.carteraDe(organizacionDe(user));
            //  Cuando se le escribio por ultima vez a cada deudor, en una sola
            //  consulta: la empresa ve a quien falta invitar o hay que reenviarselo.
            Map<Long, Instant> enviado = cartera.isEmpty() ? Map.of()
                    : contactos.ultimoPorDeuda(cartera).stream()
                            .collect(Collectors.toMap(UltimoContacto::deuda, UltimoContacto::cuando));
            Map<Long, DebtSummaryResponse.Disputa> disputas = disputas(cartera);
            Map<Long, BigDecimal> caes = caes(cartera);
            return cartera.stream()
                    .map(d -> resumen(d, enviado.get(d.getId()), disputas.get(d.getId()), caes.get(d.getId())))
                    .toList();
        }
        List<Debt> filas = debts.findByDebtorOrderByUpdatedAtDesc(deudorDe(user));
        filas.forEach(this::anotarIngreso);
        Map<Long, DebtSummaryResponse.Disputa> disputas = disputas(filas);
        Map<Long, BigDecimal> caes = caes(filas);
        return filas.stream()
                .map(d -> resumen(d, null, disputas.get(d.getId()), caes.get(d.getId())))
                .toList();
    }

    /**
     * La mora de cada cuota pendiente de la deuda, hoy. Los cargos se leen solo
     * si la deuda genera intereses, que son las menos.
     */
    Map<Long, BigDecimal> mora(Debt deuda, List<Installment> cuotas) {
        if (deuda.getInterestRate() == null) {
            return Map.of();
        }
        return Intereses.deMora(deuda, charges.findByDebtOrderByDueDateAsc(deuda), cuotas, LocalDate.now(CHILE));
    }

    /**
     * La mora que se condona si hoy se paga toda la deuda (contrato §7.1): la
     * mora del dia por el % de su tramo, redondeada como el resto. Cero en
     * convenio, sin mora o sin una campana que ofrezca descuento.
     */
    private BigDecimal descuentoDelDia(Debt deuda, List<Installment> pendientes, BigDecimal moraTotal) {
        if (deuda.getStatus() != Debt.Status.open || moraTotal.signum() <= 0 || deuda.getCampaign() == null
                || deuda.getCampaign().getMoraDiscount() == null
                || pendientes.stream().anyMatch(Installment::enConvenio)) {
            return BigDecimal.ZERO;
        }
        LocalDate hoy = LocalDate.now(CHILE);
        long dias = Intereses.diasDeMora(charges.findByDebtOrderByDueDateAsc(deuda), pendientes, hoy);
        BigDecimal porcentaje = descuentos.porcentaje(deuda, dias, hoy);
        if (porcentaje == null || porcentaje.signum() <= 0) {
            return BigDecimal.ZERO;
        }
        return Intereses.redondear(moraTotal.multiply(porcentaje).divide(CIEN), deuda.getCurrency());
    }

    /** El descuento que tiene hoy la deuda, para mostrarlo antes de pagar. Null sin descuento. */
    private DebtSummaryResponse.Oferta oferta(Debt deuda, List<Installment> cuotas, BigDecimal moraTotal) {
        List<Installment> pendientes = cuotas.stream().filter(c -> c.getStatus() == Installment.Status.pending)
                .toList();
        BigDecimal descuento = descuentoDelDia(deuda, pendientes, moraTotal);
        return descuento.signum() > 0 ? new DebtSummaryResponse.Oferta(descuento, deuda.getCampaign().getEndsOn())
                : null;
    }

    /** Lo que la deuda tiene de capital pendiente: las cuotas, sin el interes del convenio que aun no corre. */
    private BigDecimal saldoCapital(List<Installment> cuotas) {
        return cuotas.stream()
                .filter(c -> c.getStatus() == Installment.Status.pending)
                .map(Installment::capital)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * La CAE del convenio vigente de cada deuda, en una sola consulta. Si por
     * algo quedaran dos vigentes, vale el que se acepto despues. El resumen la
     * muestra solo si la deuda esta en convenio.
     */
    private Map<Long, BigDecimal> caes(List<Debt> deudas) {
        if (deudas.isEmpty()) {
            return Map.of();
        }
        Map<Long, Repactation> vigente = new HashMap<>();
        for (Repactation convenio : repactations.findByDebtInAndSupersededAtIsNull(deudas)) {
            vigente.merge(convenio.getDebt().getId(), convenio,
                    (a, b) -> b.getAcceptedAt().isAfter(a.getAcceptedAt()) ? b : a);
        }
        Map<Long, BigDecimal> caes = new HashMap<>();
        vigente.forEach((deuda, convenio) -> {
            if (convenio.getCae() != null) {
                caes.put(deuda, convenio.getCae());
            }
        });
        return caes;
    }

    /** La disputa abierta de cada deuda que esta en disputa: la ultima que abrio el deudor. */
    private Map<Long, DebtSummaryResponse.Disputa> disputas(List<Debt> deudas) {
        List<Debt> enDisputa = deudas.stream().filter(d -> d.getStatus() == Debt.Status.disputed).toList();
        Map<Long, DebtSummaryResponse.Disputa> porDeuda = new HashMap<>();
        if (enDisputa.isEmpty()) {
            return porDeuda;
        }
        for (DebtEvent evento : events.findByDebtInAndType(enDisputa, DebtEvent.Type.disputed)) {
            DebtSummaryResponse.Disputa disputa = new DebtSummaryResponse.Disputa(evento.getReference(),
                    detalleDe(evento), evento.getOccurredAt());
            porDeuda.merge(evento.getDebt().getId(), disputa, (a, b) -> a.desde().isAfter(b.desde()) ? a : b);
        }
        return porDeuda;
    }

    private String detalleDe(DebtEvent evento) {
        if (evento.getDetail() == null) {
            return null;
        }
        try {
            return json.readTree(evento.getDetail()).path("detalle").asText(null);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return null;
        }
    }

    /**
     * Las deudas que ve quien pregunta, sin mas: la empresa, su cartera; el
     * deudor, las suyas. Para los servicios que arman sus propias vistas
     * (historial, vencimientos, convenios en riesgo).
     */
    List<Debt> deudasVisibles(JwtPrincipal user) {
        if (user != null && user.isCreditor()) {
            return debts.carteraDe(organizacionDe(user));
        }
        return debts.findByDebtorOrderByUpdatedAtDesc(deudorDe(user));
    }

    /**
     * El deudor entro a ver su deuda. Se anota una vez al dia por deuda: es lo
     * que la agencia mide como "ingresos al portal" y, con codigo de acceso,
     * reemplaza al clic en el enlace que ya no existe.
     */
    private void anotarIngreso(Debt deuda) {
        Instant desdeHoy = LocalDate.now(CHILE).atStartOfDay(CHILE).toInstant();
        if (!events.existsByDebtAndTypeAndOccurredAtAfter(deuda, DebtEvent.Type.portal_entered, desdeHoy)) {
            events.save(DebtEvent.de(deuda, DebtEvent.Type.portal_entered, DebtEvent.Actor.debtor));
        }
    }

    public DebtDetailResponse getFor(JwtPrincipal user, Long id) {
        return detalle(requireVisible(user, id));
    }

    /**
     * La deuda, si a quien pregunta le corresponde verla. La empresa solo ve
     * las de su cartera; el deudor, solo las propias.
     */
    public Debt requireVisible(JwtPrincipal user, Long id) {
        Debt deuda = debts.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Deuda no encontrada"));
        if (user == null) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Sesion invalida");
        }
        if (user.isCreditor()) {
            if (!opera(organizacionDe(user), deuda)) {
                throw new ApiException(HttpStatus.FORBIDDEN, "Esa deuda no es de tu cartera");
            }
            return deuda;
        }
        if (!deuda.getDebtor().getId().equals(deudorDe(user).getId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "No puedes ver esta deuda");
        }
        return deuda;
    }

    /** La misma regla que {@code DebtRepository.carteraDe}, para una deuda suelta. */
    private static boolean opera(Organization organizacion, Debt deuda) {
        return deuda.getCreditor().getId().equals(organizacion.getId())
                || deuda.getLastBatch().getSender().getId().equals(organizacion.getId());
    }

    /** Lo que se debe hoy: la suma de las cuotas pendientes. */
    public BigDecimal saldo(Debt deuda) {
        return installments.findByDebtAndStatus(deuda, Installment.Status.pending).stream()
                .map(Installment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    // ------------------------------------------------------------------
    //  Repactacion
    // ------------------------------------------------------------------

    public RepactPlan simulate(JwtPrincipal user, Long id, int months) {
        return plan(requireVisible(user, id), months);
    }

    /**
     * El plan para una deuda: se repacta el capital pendiente mas la mora de
     * hoy, con la tasa que pacto el acreedor. El interes del convenio que las
     * cuotas pendientes traian se deja fuera: todavia no corria.
     */
    private RepactPlan plan(Debt deuda, int months) {
        List<Installment> cuotas = installments.findByDebtOrderByNumberAsc(deuda);
        return repactation.simulate(saldoCapital(cuotas), Intereses.total(mora(deuda, cuotas)),
                deuda.getInterestRate(), deuda.getCurrency(), months, LocalDate.now(CHILE).plusMonths(1));
    }

    /**
     * El deudor acepta un plan.
     *
     * <p>Las cuotas pendientes se ANULAN, no se borran: un compromiso que
     * existio deja rastro. Y el plan anterior queda marcado como reemplazado,
     * por la misma razon.
     */
    @Transactional
    public DebtDetailResponse applyRepact(JwtPrincipal user, Long id, int months) {
        if (user != null && user.isCreditor()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "La repactacion la acepta el deudor");
        }
        Debt deuda = requireVisible(user, id);
        if (deuda.getStatus() == Debt.Status.paid) {
            throw new ApiException(HttpStatus.CONFLICT, "La deuda ya esta pagada");
        }
        if (deuda.getStatus() == Debt.Status.withdrawn) {
            throw new ApiException(HttpStatus.CONFLICT, "El acreedor retiro esta deuda de la cobranza");
        }
        if (deuda.getStatus() == Debt.Status.disputed) {
            throw new ApiException(HttpStatus.CONFLICT, "La deuda esta en revision: no se repacta mientras tanto");
        }

        RepactPlan plan = plan(deuda, months);
        boolean conInteres = plan.tasaInteresMensual() != null;

        for (Repactation anterior : repactations.findByDebtAndSupersededAtIsNull(deuda)) {
            anterior.setSupersededAt(Instant.now());
            repactations.save(anterior);
        }
        Repactation nueva = new Repactation();
        nueva.setDebt(deuda);
        nueva.setMonths((short) months);
        nueva.setMonthlyAmount(plan.monthlyAmount());
        nueva.setInterestRate(plan.tasaInteresMensual());
        nueva.setPrincipal(plan.aRepactar());
        nueva.setCae(plan.cae());
        nueva.setCurrency(deuda.getCurrency());
        repactations.save(nueva);

        anularPendientes(deuda);
        short numero = siguienteNumero(deuda);
        for (InstallmentPreview previa : plan.cuotas()) {
            Installment cuota = new Installment();
            cuota.setDebt(deuda);
            cuota.setRepactation(nueva);
            cuota.setNumber(numero++);
            cuota.setDueDate(previa.dueDate());
            cuota.setAmount(previa.amount());
            cuota.setInterestAmount(previa.interest());
            cuota.setStatus(Installment.Status.pending);
            installments.save(cuota);
        }

        deuda.setStatus(Debt.Status.repacted);
        deuda.setUpdatedAt(Instant.now());
        debts.save(deuda);

        events.save(DebtEvent.de(deuda, DebtEvent.Type.repacted, DebtEvent.Actor.debtor)
                .conMonto(plan.monthlyAmount(), deuda.getCurrency())
                .conReferencia(months + " cuotas"));
        eventos.publicar(deuda, EventosService.REPACTACION_ACEPTADA, new RepactacionAceptadaDatos(
                deuda.getExternalId(), months, EventosService.monto(plan.monthlyAmount(), deuda.getCurrency()),
                deuda.getCurrency().name(), plan.cuotas().getFirst().dueDate().toString(),
                plan.tasaInteresMensual(),
                conInteres ? EventosService.monto(plan.aRepactar(), deuda.getCurrency()) : null,
                conInteres ? EventosService.monto(plan.total(), deuda.getCurrency()) : null,
                plan.cae()), Instant.now());

        return detalle(deuda);
    }

    private void anularPendientes(Debt deuda) {
        for (Installment pendiente : installments.findByDebtAndStatus(deuda, Installment.Status.pending)) {
            pendiente.setStatus(Installment.Status.void_);
            installments.save(pendiente);
        }
    }

    /** Las cuotas se numeran corrido, para que dos planes no choquen. */
    private short siguienteNumero(Debt deuda) {
        return (short) (installments.findByDebtOrderByNumberAsc(deuda).stream()
                .mapToInt(Installment::getNumber).max().orElse(0) + 1);
    }

    // ------------------------------------------------------------------
    //  Lo que ms-payments necesita y lo que avisa
    // ------------------------------------------------------------------

    /**
     * Cuanto se debe y a quien, para que ms-payments no le crea al navegador.
     *
     * <p><b>Las cuotas se pagan en orden.</b> Se pueden pagar varias a la vez,
     * pero siempre las que vencen primero: pagar la de diciembre dejando
     * octubre impaga dejaria al deudor en mora con plata pagada. Sin cuotas
     * indicadas se cobran todas, es decir, el saldo.
     *
     * <p>La cuota va en la respuesta solo cuando se paga una. Con varias va
     * vacia, y al confirmarse el pago se imputa de la mas antigua a la mas
     * nueva: como el monto es justo la suma de las elegidas, paga esas y
     * ninguna otra.
     *
     * <p>Si la deuda genera intereses, el monto es el capital de las cuotas mas
     * su mora de hoy, y la respuesta trae las dos partes.
     */
    public DebtSnapshotResponse snapshotInterno(Long debtId, List<Long> installmentIds) {
        Debt deuda = debts.findById(debtId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Deuda no encontrada"));
        //  Mientras la empresa revisa la disputa, no se cobra.
        if (deuda.getStatus() == Debt.Status.disputed) {
            throw new ApiException(HttpStatus.CONFLICT, "La deuda esta en revision: no se puede pagar mientras tanto");
        }
        List<Installment> pendientes = installments.findByDebtAndStatus(deuda, Installment.Status.pending).stream()
                .sorted(EN_ORDEN).toList();

        List<Installment> aPagar = pendientes;
        if (installmentIds != null && !installmentIds.isEmpty()) {
            Set<Long> pedidas = new HashSet<>(installmentIds);
            if (pedidas.size() != installmentIds.size()) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "Una cuota viene dos veces");
            }
            Map<Long, Installment> pendientePorId = pendientes.stream()
                    .collect(Collectors.toMap(Installment::getId, Function.identity()));
            for (Long id : installmentIds) {
                if (!pendientePorId.containsKey(id)) {
                    throw cuotaQueNoSePuedePagar(deuda, id);
                }
            }
            aPagar = pendientes.subList(0, pedidas.size());
            if (!aPagar.stream().map(Installment::getId).collect(Collectors.toSet()).equals(pedidas)) {
                throw new ApiException(HttpStatus.CONFLICT,
                        "Las cuotas se pagan en orden, desde la que vence primero");
            }
        }

        BigDecimal capital = aPagar.stream().map(Installment::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (capital.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException(HttpStatus.CONFLICT, "Esa deuda no tiene saldo por pagar");
        }
        Map<Long, BigDecimal> mora = mora(deuda, pendientes);
        BigDecimal interes = aPagar.stream().map(c -> mora.getOrDefault(c.getId(), BigDecimal.ZERO))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        //  El descuento por pronto pago es solo para quien paga toda la deuda, y
        //  queda fijo desde aca: si la pasarela confirma despues, no cambia.
        BigDecimal descuento = aPagar.size() == pendientes.size()
                ? descuentoDelDia(deuda, pendientes, interes) : BigDecimal.ZERO;
        interes = interes.subtract(descuento);
        Long cuotaId = aPagar.size() == 1 ? aPagar.getFirst().getId() : null;
        return new DebtSnapshotResponse(deuda.getId(), deuda.getCreditor().getRut(), deuda.getDebtor().getRut(),
                deuda.getCurrency().name(), capital.add(interes), cuotaId,
                aPagar.stream().map(Installment::getId).toList(), capital, interes, descuento);
    }

    /** Por que una cuota pedida no esta entre las que se pueden pagar. */
    private ApiException cuotaQueNoSePuedePagar(Debt deuda, Long id) {
        Installment cuota = installments.findById(id).orElse(null);
        if (cuota == null) {
            return new ApiException(HttpStatus.NOT_FOUND, "Cuota no encontrada");
        }
        if (!cuota.getDebt().getId().equals(deuda.getId())) {
            return new ApiException(HttpStatus.BAD_REQUEST, "Esa cuota no es de esa deuda");
        }
        return new ApiException(HttpStatus.CONFLICT, "La cuota " + cuota.getNumber() + " ya no esta pendiente");
    }

    /**
     * Un pago confirmado por ms-payments.
     *
     * <p>Idempotente a proposito: el aviso se entrega "al menos una vez", asi
     * que el mismo pago puede llegar dos veces y la segunda no debe abonar de
     * nuevo. Se reconoce por la referencia de la pasarela, que ya quedo
     * guardada en el evento.
     */
    @Transactional
    public void onPagoConfirmado(PagoConfirmado aviso) {
        if (aviso == null || aviso.debtId() == null) {
            return;
        }
        Debt deuda = debts.findById(aviso.debtId()).orElse(null);
        if (deuda == null) {
            log.warn("Aviso de pago para una deuda que no existe: {}", aviso.debtId());
            return;
        }

        String referencia = aviso.gateway() + ":" + aviso.gatewayTxnId();
        boolean yaAplicado = events.findByDebtOrderByOccurredAtAsc(deuda).stream()
                .anyMatch(e -> e.getType() == DebtEvent.Type.payment_applied && referencia.equals(e.getReference()));
        if (yaAplicado) {
            log.info("Aviso repetido del pago {}: no se abona de nuevo", aviso.paymentId());
            return;
        }

        //  Las cuotas vigentes, en el orden en que se pagan: de aca sale el lugar
        //  de cada una ("la 4 de 6") para el historial del deudor.
        List<Installment> vigentes = installments.findByDebtOrderByNumberAsc(deuda).stream()
                .filter(c -> c.getStatus() != Installment.Status.void_)
                .sorted(EN_ORDEN).toList();
        List<Installment> pagadas = new ArrayList<>();

        if (aviso.installmentIds() != null && !aviso.installmentIds().isEmpty()) {
            //  El pago dice que cuotas cobro: esas, y ninguna otra. El monto ya
            //  no sirve para imputar, porque trae la mora.
            for (Long id : aviso.installmentIds()) {
                installments.findById(id)
                        .filter(c -> c.getDebt().getId().equals(deuda.getId()))
                        .filter(c -> c.getStatus() == Installment.Status.pending)
                        .ifPresent(cuota -> {
                            marcarPagada(cuota, aviso.paidAt());
                            pagadas.add(cuota);
                        });
            }
        } else if (aviso.installmentId() != null) {
            installments.findById(aviso.installmentId())
                    .filter(c -> c.getDebt().getId().equals(deuda.getId()))
                    .filter(c -> c.getStatus() == Installment.Status.pending)
                    .ifPresent(cuota -> {
                        marcarPagada(cuota, aviso.paidAt());
                        pagadas.add(cuota);
                    });
        } else {
            //  Sin cuota indicada, se imputa de la mas antigua a la mas nueva,
            //  como cualquier abono en una cuenta corriente.
            BigDecimal porAplicar = aviso.amount() == null ? BigDecimal.ZERO : aviso.amount();
            List<Installment> pendientes = installments.findByDebtAndStatus(deuda, Installment.Status.pending).stream()
                    .sorted(EN_ORDEN).toList();
            for (Installment cuota : pendientes) {
                if (porAplicar.compareTo(cuota.getAmount()) < 0) {
                    break;
                }
                marcarPagada(cuota, aviso.paidAt());
                pagadas.add(cuota);
                porAplicar = porAplicar.subtract(cuota.getAmount());
            }
        }

        Debt.Currency moneda = Debt.Currency.valueOf(aviso.currency());
        Instant pagadoEn = aviso.paidAt() == null ? Instant.now() : aviso.paidAt();
        //  Fechado cuando se pago, no cuando llego el aviso: el aviso puede
        //  tardar (reintentos) y el historial cuenta cuando pago el deudor.
        DebtEvent aplicado = DebtEvent.de(deuda, DebtEvent.Type.payment_applied, DebtEvent.Actor.system)
                .conMonto(aviso.amount(), moneda)
                .conReferencia(referencia)
                .conDetalle(detalleDelPago(deuda, aviso, vigentes, pagadas));
        aplicado.setOccurredAt(pagadoEn);
        events.save(aplicado);
        eventos.publicar(deuda, EventosService.PAGO_CONFIRMADO, datosDelPago(deuda, aviso, moneda, pagadoEn), pagadoEn);

        if (saldo(deuda).compareTo(BigDecimal.ZERO) == 0) {
            deuda.setStatus(Debt.Status.paid);
            events.save(DebtEvent.de(deuda, DebtEvent.Type.settled, DebtEvent.Actor.system));
            eventos.publicar(deuda, EventosService.DEUDA_SALDADA,
                    new DeudaSaldadaDatos(deuda.getExternalId(), EventosService.enChile(pagadoEn)), pagadoEn);
        }
        deuda.setUpdatedAt(Instant.now());
        debts.save(deuda);
    }

    /**
     * Lo que el acreedor necesita para imputar el pago en su propio sistema.
     * En UF va el valor usado: sin el, nadie podria reconstruir por que UF
     * 38,5 fueron esos pesos.
     */
    private static PagoConfirmadoDatos datosDelPago(Debt deuda, PagoConfirmado aviso, Debt.Currency moneda,
                                                    Instant pagadoEn) {
        //  Sin mora no van ni capital ni interes: el evento queda como siempre. Con
        //  descuento si van, aunque se haya condonado toda la mora (interes en cero).
        boolean conDescuento = aviso.discount() != null && aviso.discount().signum() > 0;
        BigDecimal interes = aviso.interest() == null ? BigDecimal.ZERO : aviso.interest();
        boolean conInteres = interes.signum() > 0 || conDescuento;
        BigDecimal capital = conInteres && aviso.amount() != null ? aviso.amount().subtract(interes) : null;
        return new PagoConfirmadoDatos(
                deuda.getExternalId(),
                String.valueOf(aviso.paymentId()),
                EventosService.monto(aviso.amount(), moneda),
                moneda.name(),
                aviso.amountClp(),
                moneda == Debt.Currency.UF ? aviso.ufValue() : null,
                aviso.gateway() == null ? null : aviso.gateway().toLowerCase(),
                EventosService.enChile(pagadoEn),
                EventosService.monto(capital, moneda),
                conInteres ? EventosService.monto(interes, moneda) : null,
                conDescuento ? EventosService.monto(aviso.discount(), moneda) : null);
    }

    /**
     * El {@link DetallePago} de este aviso, en JSON. Si no se puede escribir, el pago igual se aplica.
     *
     * <p>En convenio, el lugar de cada cuota se cuenta dentro del plan ("la 4 de 6"), y una cuota aparte
     * se cuenta aparte. Sin convenio, el pago es el total de la deuda.
     */
    private String detalleDelPago(Debt deuda, PagoConfirmado aviso, List<Installment> vigentes,
                                  List<Installment> pagadas) {
        List<Long> plan = deuda.getStatus() != Debt.Status.repacted ? List.of()
                : vigentes.stream().filter(Installment::enConvenio).map(Installment::getId).toList();
        List<Integer> lugares;
        int de;
        long fuera = 0;
        if (plan.isEmpty()) {
            lugares = pagadas.isEmpty() ? List.of() : List.of(1);
            de = 1;
        } else {
            lugares = pagadas.stream()
                    .filter(Installment::enConvenio)
                    .map(c -> plan.indexOf(c.getId()) + 1)
                    .filter(lugar -> lugar > 0)
                    .sorted()
                    .toList();
            de = plan.size();
            fuera = pagadas.stream().filter(c -> !c.enConvenio()).count();
        }
        DetallePago detalle = new DetallePago(aviso.paymentId(), aviso.amountClp(), aviso.ufValue(),
                aviso.gateway() == null ? null : aviso.gateway().toLowerCase(Locale.ROOT), lugares, de,
                fuera == 0 ? null : (int) fuera,
                aviso.interest() == null || aviso.interest().signum() == 0 ? null : aviso.interest(),
                aviso.discount() == null || aviso.discount().signum() == 0 ? null : aviso.discount(),
                deuda.getLastBatch().getSender().getRut(), deuda.getLastBatch().getSender().getTradeName(),
                deuda.getCampaign() == null ? null : deuda.getCampaign().getExternalId(),
                deuda.getCampaign() == null ? null : deuda.getCampaign().getName());
        try {
            return json.writeValueAsString(detalle);
        } catch (JsonProcessingException e) {
            log.warn("No se pudo guardar el detalle del pago {}: {}", aviso.paymentId(), e.getMessage());
            return null;
        }
    }

    private void marcarPagada(Installment cuota, Instant cuando) {
        cuota.setStatus(Installment.Status.paid);
        cuota.setPaidAt(cuando == null ? Instant.now() : cuando);
        installments.save(cuota);
    }

    // ------------------------------------------------------------------
    //  Representacion
    // ------------------------------------------------------------------

    /** Con una sola consulta de cuotas: el saldo, la mora y el avance salen de la misma lista. */
    private DebtSummaryResponse resumen(Debt deuda, Instant codigoEnviado, DebtSummaryResponse.Disputa disputa,
                                        BigDecimal cae) {
        List<Installment> cuotas = installments.findByDebtOrderByNumberAsc(deuda);
        BigDecimal moraTotal = Intereses.total(mora(deuda, cuotas));
        return DebtSummaryResponse.from(deuda, cuotas, codigoEnviado, disputa, moraTotal,
                oferta(deuda, cuotas, moraTotal), cae);
    }

    private DebtDetailResponse detalle(Debt deuda) {
        List<Installment> cuotas = installments.findByDebtOrderByNumberAsc(deuda);
        Map<Long, BigDecimal> mora = mora(deuda, cuotas);
        return new DebtDetailResponse(
                DebtSummaryResponse.from(deuda, cuotas, null, disputas(List.of(deuda)).get(deuda.getId()),
                        Intereses.total(mora), oferta(deuda, cuotas, Intereses.total(mora)),
                        caes(List.of(deuda)).get(deuda.getId())),
                charges.findByDebtOrderByDueDateAsc(deuda).stream().map(DebtDetailResponse.Cargo::from).toList(),
                cuotas.stream().map(c -> DebtDetailResponse.Cuota.from(c, mora.get(c.getId()))).toList(),
                historia(deuda));
    }

    /**
     * La historia de la deuda, en orden: lo que le paso, y cada vez que se le
     * escribio al deudor. Los contactos viven en su tabla y aca se leen como
     * siempre se leyeron: un suceso {@code code_sent}.
     */
    private List<DebtDetailResponse.Suceso> historia(Debt deuda) {
        List<DebtDetailResponse.Suceso> sucesos = new ArrayList<>();
        events.findByDebtOrderByOccurredAtAsc(deuda)
                .forEach(evento -> sucesos.add(DebtDetailResponse.Suceso.from(evento)));
        contactos.findByDebtOrderByOccurredAtAsc(deuda)
                .forEach(contacto -> sucesos.add(DebtDetailResponse.Suceso.from(contacto, descripcion(contacto))));
        sucesos.sort(Comparator.comparing(DebtDetailResponse.Suceso::ocurrioEn));
        return sucesos;
    }

    /** Lo que la historia dice de un contacto: que fue o, si no hay mas que decir, a donde se mando. */
    private String descripcion(Contact contacto) {
        return switch (contacto.getKind()) {
            case campaign -> {
                Campaign campana = contacto.getCampaign();
                //  El tope es el de la campana hoy; nunca menos que el toque que ya salio.
                int tope = Math.max(contacto.getTouchNumber(),
                        Math.min(campana.getAttempts(), ContactoService.cadencia(campana, json).size()));
                yield "campana " + campana.getName() + ", toque " + contacto.getTouchNumber() + " de " + tope;
            }
            case reminder -> "recordatorio de la cuota que vence el " + contacto.getInstallment().getDueDate();
            case invitation, resend -> contacto.getDestination();
        };
    }
}
