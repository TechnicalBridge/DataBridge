package com.tbridge.payments.service;

import com.tbridge.payments.exception.ApiException;
import com.tbridge.payments.security.JwtPrincipal;
import com.tbridge.payments.client.DebtClient;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.payments.client.KhipuClient;
import com.tbridge.payments.client.MercadoPagoClient;
import com.tbridge.payments.client.WebpayClient;
import com.tbridge.payments.dto.request.CheckoutRequest;
import com.tbridge.payments.dto.gateway.WebpayCommitResponse;
import com.tbridge.payments.dto.gateway.WebpayCreateResponse;
import com.tbridge.payments.dto.request.WebhookRequest;
import com.tbridge.payments.dto.response.HistoriaResponse;
import com.tbridge.payments.dto.response.PaymentEventResponse;
import com.tbridge.payments.dto.response.PaymentResponse;
import com.tbridge.payments.model.DebtNotification;
import com.tbridge.payments.model.Payment;
import com.tbridge.payments.model.PaymentEvent;
import com.tbridge.payments.model.UfValue;
import com.tbridge.payments.repository.DebtNotificationRepository;
import com.tbridge.payments.repository.PaymentEventRepository;
import com.tbridge.payments.repository.PaymentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * El cobro.
 *
 * <p>Tres reglas gobiernan este archivo:
 *
 * <ol>
 *   <li><b>El monto no lo decide el cliente.</b> Sale de ms-debt, que es quien
 *       manda sobre la deuda.</li>
 *   <li><b>Nada se sobreescribe.</b> Cada transicion queda como una fila nueva
 *       en el libro; el estado del pago es solo la proyeccion del ultimo.</li>
 *   <li><b>El aviso a ms-debt no se manda aqui.</b> Se deja encolado en la
 *       misma transaccion y sale despues, con reintentos.</li>
 * </ol>
 *
 * <p>Tres pasarelas cobran de verdad, y en las tres el pago se da por hecho solo
 * cuando la pasarela lo confirma, de servidor a servidor:
 *
 * <ul>
 *   <li><b>Webpay</b>, en el ambiente de integracion de Transbank por omision:
 *       el deudor paga con una tarjeta de prueba y ms-payments confirma la
 *       transaccion al volver.</li>
 *   <li><b>Khipu</b>, cuando hay {@code KHIPU_LLAVE}: el deudor paga con una
 *       transferencia, y se le pregunta a Khipu si esta conciliado.</li>
 *   <li><b>Mercado Pago</b>, cuando hay {@code MERCADOPAGO_ACCESS_TOKEN}: el
 *       deudor paga en Checkout Pro. No se espera su vuelta: se le pregunta a
 *       Mercado Pago por la preference abierta, que es lo que guarda que pagos
 *       se hicieron sobre ella.</li>
 * </ul>
 *
 * <p>Sin credenciales, las tres quedan simuladas: una pagina propia confirma
 * con la firma del enlace.
 */
@Service
public class PaymentService {

    private static final ZoneId CHILE = ZoneId.of("America/Santiago");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ORDEN = Pattern.compile("^ORD(\\d{1,18})(T\\d+)?$");
    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    /**
     * Cuanto tiempo despues de vencer se sigue preguntando por un cobro. Un
     * intento empezado justo antes de vencer puede terminar despues: en Khipu,
     * cada intento tiene hasta 3 horas.
     */
    private static final Duration REVISAR_VENCIDOS = Duration.ofHours(24);

    /** Los estados de un pago de Mercado Pago que todavia se pueden aprobar. */
    private static final Set<String> EN_CURSO_MERCADOPAGO = Set.of("pending", "in_process", "authorized");

    private final PaymentRepository payments;
    private final PaymentEventRepository eventos;
    private final DebtNotificationRepository avisos;
    private final WebhookVerifier verifier;
    private final DebtClient deudas;
    private final UfService uf;
    private final KhipuClient khipu;
    private final FirmaDeKhipu firmaDeKhipu;
    private final WebpayClient webpay;
    private final MercadoPagoClient mercadopago;
    private final String publicUrl;
    private final String avisosDeKhipu;
    private final Duration venceEn;
    private final Duration venceEnMercadoPago;

    /** Donde Khipu avisa que un pago se concilio. Solo sirve con una direccion publica. */
    public static final String AVISOS_KHIPU = "/api/payments/public/khipu/aviso";

    /**
     * Donde Webpay devuelve al deudor despues de pagar o de anular. Va por la
     * direccion publica (el portal manda {@code /api} al gateway): el puerto
     * del gateway no esta publicado.
     */
    public static final String RETORNO_WEBPAY = "/api/payments/public/webpay/retorno";

    /** Donde Mercado Pago devuelve al deudor despues de pagar o cancelar. */
    public static final String RETORNO_MERCADOPAGO = "/api/payments/public/mercadopago/retorno";

    public PaymentService(
            PaymentRepository payments,
            PaymentEventRepository eventos,
            DebtNotificationRepository avisos,
            WebhookVerifier verifier,
            DebtClient deudas,
            UfService uf,
            KhipuClient khipu,
            FirmaDeKhipu firmaDeKhipu,
            WebpayClient webpay,
            MercadoPagoClient mercadopago,
            @Value("${app.public-url}") String publicUrl,
            @Value("${app.khipu.url-avisos:}") String avisosDeKhipu,
            @Value("${app.khipu.vence-en:30m}") Duration venceEn,
            @Value("${app.mercadopago.vence-en:30m}") Duration venceEnMercadoPago
    ) {
        this.payments = payments;
        this.eventos = eventos;
        this.avisos = avisos;
        this.verifier = verifier;
        this.deudas = deudas;
        this.uf = uf;
        this.khipu = khipu;
        this.firmaDeKhipu = firmaDeKhipu;
        this.webpay = webpay;
        this.mercadopago = mercadopago;
        this.publicUrl = publicUrl.replaceAll("/$", "");
        this.avisosDeKhipu = avisosDeKhipu == null || avisosDeKhipu.isBlank() ? null
                : avisosDeKhipu.trim().replaceAll("/$", "");
        this.venceEn = venceEn;
        this.venceEnMercadoPago = venceEnMercadoPago;
    }

    // ------------------------------------------------------------------
    //  Iniciar el pago
    // ------------------------------------------------------------------

    @Transactional
    public PaymentResponse checkout(JwtPrincipal user, CheckoutRequest pedido) {
        if (user != null && user.isCreditor()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "El acreedor no paga deudas");
        }
        //  Solo se paga lo propio, y lo propio se decide por RUT: es lo unico
        //  que trae la sesion de un deudor, que entra con su codigo, sin
        //  cuenta ni correo.
        if (user == null || user.rut() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "La sesion no identifica al deudor");
        }
        Payment.Gateway gateway = pasarela(pedido.gateway());

        //  El monto y a quien se le debe salen de ms-debt, no del cuerpo.
        DebtClient.DebtSnapshot deuda = deudas.obtener(pedido.debtId(), pedido.installmentIds());
        if (!user.rut().equalsIgnoreCase(deuda.debtorRut())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Esa deuda no es tuya");
        }
        //  Un pago a la vez por deuda: si no, el deudor puede pagar dos veces
        //  las mismas cuotas.
        antesDeAbrirOtro(deuda.debtId());
        noPagadasYa(deuda);

        Payment pago = new Payment();
        pago.setDebtId(deuda.debtId());
        pago.setInstallmentId(deuda.installmentId());
        pago.setCuotas(deuda.installmentIds());
        pago.setDebtorRut(deuda.debtorRut());
        pago.setCreditorRut(deuda.creditorRut());
        pago.setAmount(deuda.amount());
        pago.setInterestAmount(deuda.interes() == null || deuda.interes().signum() == 0 ? null : deuda.interes());
        pago.setDiscountAmount(deuda.descuento() == null || deuda.descuento().signum() == 0 ? null : deuda.descuento());
        pago.setCurrency(Payment.Currency.valueOf(deuda.currency()));
        pago.setGateway(gateway);
        pago.setStatus(Payment.Status.created);
        pago.setCreatedAt(Instant.now());
        //  Los pesos se fijan al abrir el cobro, porque es lo que la pasarela
        //  le cobra al deudor. Calcularlos al confirmar dejaba un pago abierto
        //  a las 23:59 y confirmado a las 00:01 registrado con otra UF que la
        //  que se cobro, y la conciliacion no cuadraba.
        fijarPesos(pago);
        payments.save(pago);
        eventos.save(PaymentEvent.de(pago.getId(), PaymentEvent.Type.created, PaymentEvent.Source.portal));

        if (cobraWebpay(pago)) {
            //  La transaccion se abre en Transbank ahora; el token queda como
            //  el id del pago en la pasarela, que es como vuelve el deudor. Al
            //  deudor se lo lleva a una pagina propia que manda el token a
            //  Webpay por POST, como pide Transbank.
            WebpayCreateResponse transaccion = webpay.createTransaction(ordenDeCompra(pago),
                    sesionWebpay(pago), pago.getAmountClp(), publicUrl + RETORNO_WEBPAY);
            pago.setGatewayTxnId(transaccion.token());
            payments.save(pago);
            return respuesta(pago).conEnlaceDePago(
                    publicUrl + "/api/payments/public/" + pago.getId() + "/webpay?sig=" + firma(pago));
        }
        if (cobraKhipu(pago)) {
            //  El cobro se abre en Khipu ahora, y su id queda como el del pago
            //  en la pasarela. Khipu devuelve al deudor a la pagina del
            //  resultado, que le pregunta a Khipu en que quedo.
            KhipuClient.Cobro cobro = khipu.crear(transaccion(pago), "Pago de deuda en cobranza N° " + pago.getDebtId(),
                    pago.getAmountClp(), enlaceDePago(pago), enlaceDePago(pago) + "&cancelado=1",
                    avisosDeKhipu == null ? null : avisosDeKhipu + AVISOS_KHIPU,
                    ZonedDateTime.now(CHILE).plus(venceEn).toOffsetDateTime());
            pago.setGatewayTxnId(cobro.paymentId());
            payments.save(pago);
            return respuesta(pago).conEnlaceDePago(cobro.paymentUrl());
        }
        if (cobraMercadoPago(pago)) {
            //  El cobro se abre en Mercado Pago ahora (Checkout Pro).
            String returnUrl = publicUrl + RETORNO_MERCADOPAGO + "?pago=" + pago.getId() + "&sig=" + firma(pago);
            MercadoPagoClient.Preferencia pref = mercadopago.crearPreferencia(
                    String.valueOf(pago.getId()),
                    "Pago de deuda N° " + pago.getDebtId(),
                    pago.getAmountClp(),
                    user != null ? user.email() : null,
                    returnUrl,
                    ZonedDateTime.now(CHILE).plus(venceEnMercadoPago).toOffsetDateTime()
            );
            pago.setGatewayTxnId(pref.id());
            payments.save(pago);
            return respuesta(pago).conEnlaceDePago(pref.url(mercadopago.testMode()));
        }
        return respuesta(pago).conEnlaceDePago(enlaceDePago(pago));
    }

    /**
     * Antes de abrir un pago, los otros pagos abiertos de la misma deuda.
     *
     * <ul>
     *   <li>Uno que la pasarela esta verificando (el deudor ya pago y falta que
     *       la pasarela lo confirme) frena el nuevo: pagar de nuevo seria
     *       cobrarle dos veces.</li>
     *   <li>Uno abierto sin pagar se anula en la pasarela, para que no se pueda
     *       pagar ademas del nuevo, y queda vencido.</li>
     *   <li>Uno ya pagado se registra; {@link #noPagadasYa} frena despues el
     *       pago nuevo si cubre esas cuotas.</li>
     * </ul>
     *
     * <p>Uno de Webpay se mira en Transbank: si ya se pago, se registra; si no,
     * queda vencido, y si igual se pagara despues no se confirma. La simulada no
     * cobra: esos se dejan. Si igual se pagaran los dos, el segundo queda como
     * duplicado ({@link #confirmar}).
     */
    private void antesDeAbrirOtro(Long debtId) {
        for (Payment otro : payments.findByDebtIdAndStatus(debtId, Payment.Status.created)) {
            boolean enVerificacion;
            if (cobraKhipu(otro)) {
                enVerificacion = conciliar(otro);
                if (!enVerificacion && otro.getStatus() == Payment.Status.created) {
                    //  Si Khipu no deja anularlo, alguien lo esta pagando: se pregunta de nuevo.
                    khipu.anular(otro.getGatewayTxnId());
                    enVerificacion = conciliar(otro);
                }
            } else if (cobraMercadoPago(otro)) {
                enVerificacion = conciliarMercadoPago(otro);
                if (!enVerificacion && otro.getStatus() == Payment.Status.created) {
                    mercadopago.vencerPreferencia(otro.getGatewayTxnId());
                    enVerificacion = conciliarMercadoPago(otro);
                }
            } else if (cobraWebpay(otro)) {
                //  Puede que el deudor ya haya pagado en la ventana anterior (la
                //  que quedo detras): se le pregunta a Transbank y, si pago, se
                //  registra y noPagadasYa frena el cobro nuevo. Si no pago, ese
                //  cobro se vence: si igual pagara ahi, no se confirmaria y
                //  Transbank lo reversa, en vez de cobrarle dos veces.
                try {
                    segunTransbank(otro, true);
                } catch (ApiException transbankNoResponde) {
                    //  Sin respuesta no se sabe: se deja, y si pagara los dos, el
                    //  segundo queda duplicado (confirmar).
                    continue;
                }
                if (otro.getStatus() == Payment.Status.created) {
                    vencer(otro);
                }
                continue;
            } else {
                continue;
            }
            if (enVerificacion) {
                throw new ApiException(HttpStatus.CONFLICT, "Tienes un pago en verificación en " + nombre(otro)
                        + ": espera a que se confirme antes de pagar de nuevo.");
            }
            if (otro.getStatus() == Payment.Status.created) {
                vencer(otro);
            }
        }
    }

    /**
     * Si otro pago ya cubrio alguna de estas cuotas. ms-debt la abona unos
     * segundos despues de confirmarse el pago, y mientras tanto la sigue
     * mostrando pendiente: sin esto se podia pagar dos veces.
     */
    private void noPagadasYa(DebtClient.DebtSnapshot deuda) {
        Set<Long> cuotas = deuda.installmentIds() == null ? Set.of() : new HashSet<>(deuda.installmentIds());
        boolean yaPagadas = payments.findByDebtIdAndStatus(deuda.debtId(), Payment.Status.paid).stream()
                .anyMatch(otro -> !Collections.disjoint(otro.cuotas(), cuotas));
        if (yaPagadas) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Esas cuotas ya están pagadas: el pago se está registrando y en unos segundos se verá en tu deuda.");
        }
    }

    private String enlaceDePago(Payment pago) {
        return publicUrl + "/pasarela/" + pago.getId() + "?sig=" + firma(pago);
    }

    private boolean cobraKhipu(Payment pago) {
        return pago.getGateway() == Payment.Gateway.khipu && khipu.real();
    }

    private boolean cobraWebpay(Payment pago) {
        return pago.getGateway() == Payment.Gateway.webpay && webpay.real();
    }

    private boolean cobraMercadoPago(Payment pago) {
        return pago.getGateway() == Payment.Gateway.mercadopago && mercadopago.real();
    }

    /** Si este pago lo cobra una pasarela real y no la simulacion. */
    private boolean cobraDeVerdad(Payment pago) {
        return cobraKhipu(pago) || cobraWebpay(pago) || cobraMercadoPago(pago);
    }

    private static String nombre(Payment pago) {
        return switch (pago.getGateway()) {
            case webpay -> "Webpay";
            case khipu -> "Khipu";
            case mercadopago -> "Mercado Pago";
        };
    }

    /**
     * La orden de compra en Transbank: con el id del pago adentro, que es como
     * se encuentra el pago cuando Webpay vuelve sin token (se acabo el tiempo).
     * La marca de tiempo la hace unica aunque la base se reinicie.
     */
    private static String ordenDeCompra(Payment pago) {
        return "ORD" + pago.getId() + "T" + (System.currentTimeMillis() % 100000);
    }

    private PaymentResponse respuesta(Payment pago) {
        return PaymentResponse.from(pago, !cobraDeVerdad(pago)).conVence(venceA(pago));
    }

    /**
     * Hasta cuando se puede pagar un cobro abierto: lo que da cada pasarela.
     * Null si ya se cerro o si es la simulacion, que no vence.
     */
    private Instant venceA(Payment pago) {
        if (pago.getStatus() != Payment.Status.created || pago.getCreatedAt() == null || !cobraDeVerdad(pago)) {
            return null;
        }
        Duration plazo = cobraWebpay(pago) ? webpay.plazoDePago()
                : cobraKhipu(pago) ? venceEn : venceEnMercadoPago;
        return plazo == null ? null : pago.getCreatedAt().plus(plazo);
    }

    /**
     * La sesion del cobro en Transbank: una firma del pago, que solo puede
     * calcular ms-payments. Cuando al deudor se le acaba el tiempo, Webpay lo
     * devuelve sin token, solo con la orden de compra y esta sesion: la orden
     * lleva el numero del pago, que se adivina, y sin la sesion cualquiera
     * podria hacer fallar el cobro abierto de otro deudor.
     */
    private String sesionWebpay(Payment pago) {
        return "DB" + verifier.sign("sesion-" + pago.getId(), pago.getAmount().toPlainString(),
                String.valueOf(pago.getDebtId())).substring(0, 40);
    }

    /** El id de la transaccion en Khipu: corto, y con el id del pago adentro. */
    private static String transaccion(Payment pago) {
        return "TB-" + pago.getId();
    }

    /**
     * La firma del enlace de pago. No se guarda: se recalcula. Guardar una
     * firma que se puede derivar es una copia mas que puede desincronizarse.
     */
    private String firma(Payment pago) {
        return verifier.sign(String.valueOf(pago.getId()), pago.getAmount().toPlainString(),
                String.valueOf(pago.getDebtId()));
    }

    // ------------------------------------------------------------------
    //  Consultar
    // ------------------------------------------------------------------

    /**
     * Un pago, si a quien pregunta le corresponde verlo: al deudor, los suyos;
     * a la empresa, los de su cartera. Todo por RUT, que es lo que traen las
     * sesiones.
     */
    public PaymentResponse get(JwtPrincipal user, Long id) {
        return respuesta(visible(user, id));
    }

    public PaymentResponse publicGet(Long id, String sig) {
        return respuesta(conFirmaValida(id, sig));
    }

    /** Lo que ve cada quien: el acreedor, SOLO lo suyo. */
    public List<PaymentResponse> list(JwtPrincipal user) {
        if (user == null || user.rut() == null) {
            return List.of();
        }
        List<Payment> filas = user.isCreditor()
                ? payments.findByCreditorRutOrderByCreatedAtDesc(user.rut())
                : payments.findByDebtorRutOrderByCreatedAtDesc(user.rut());
        return filas.stream().map(this::respuesta).toList();
    }

    /**
     * Los pagos duplicados de la cartera de una empresa, para devolverlos. La
     * cartera la decide ms-debt, como en Pagos recibidos: la agencia ve la que
     * entrego, no solo aquella de la que es acreedora.
     */
    public List<PaymentResponse> paraDevolver(JwtPrincipal user) {
        if (user == null || !user.isCreditor() || user.rut() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Solo una empresa ve sus pagos para devolver");
        }
        List<Long> cartera = deudas.cartera(user.rut());
        if (cartera.isEmpty()) {
            return List.of();
        }
        return payments.findByStatusAndDebtIdInOrderByCreatedAtDesc(Payment.Status.duplicated, cartera).stream()
                .map(this::respuesta)
                .toList();
    }

    /** La referencia de la devolucion: lo que dio la pasarela, sin pasar de este largo. */
    public static final int MAX_REFERENCIA = 100;

    /**
     * Registra que la empresa ya devolvio un pago duplicado en la pasarela. El
     * pago pasa a {@code refunded} y su libro anota un evento manual con la
     * referencia, la empresa y la persona que lo registro. DataBridge no mueve
     * plata: solo deja constancia de lo que la empresa hizo alla.
     *
     * <p>Marcar de nuevo un pago ya devuelto no cambia nada.
     */
    @Transactional
    public PaymentResponse registrarDevolucion(JwtPrincipal user, Long id, String referencia) {
        if (user == null || !user.isCreditor() || user.rut() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Solo una empresa puede registrar la devolucion de un pago");
        }
        Payment pago = buscar(id);
        if (!deudas.cartera(user.rut()).contains(pago.getDebtId()) && !mismo(user.rut(), pago.getCreditorRut())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Este pago no es de tu cartera");
        }
        if (pago.getStatus() == Payment.Status.refunded) {
            return conSuDevolucion(pago);
        }
        if (pago.getStatus() != Payment.Status.duplicated) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "Solo se puede registrar la devolucion de un pago duplicado");
        }
        String ref = referencia == null ? "" : referencia.strip();
        if (ref.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Falta la referencia de la devolucion en la pasarela");
        }
        if (ref.length() > MAX_REFERENCIA) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                    "La referencia de la devolucion tiene mas de " + MAX_REFERENCIA + " caracteres");
        }
        pago.setStatus(Payment.Status.refunded);
        payments.save(pago);

        //  El JSON lo arma Jackson: una referencia con comillas no rompe el libro ni le agrega campos.
        Map<String, String> libro = new LinkedHashMap<>();
        libro.put("referencia", ref);
        libro.put("empresa", user.rut());
        libro.put("registradoPor", user.email() != null ? user.email() : user.id());
        try {
            eventos.save(PaymentEvent.de(pago.getId(), PaymentEvent.Type.refunded, PaymentEvent.Source.manual)
                    .conRespuesta(JSON.writeValueAsString(libro)));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("No se pudo anotar la devolucion", e);
        }
        log.info("Pago {}: la empresa {} registro su devolucion", pago.getId(), user.rut());
        return conSuDevolucion(pago);
    }

    /**
     * Los pagos ya devueltos, del mas nuevo al mas antiguo, con cuando y con que
     * referencia: a la empresa, los de su cartera; al deudor, los suyos. Un pago
     * duplicado nunca se abona, asi que no aparece en el historial de ms-debt:
     * el deudor se entera aqui de que se le devolvio.
     */
    public List<PaymentResponse> devueltos(JwtPrincipal user) {
        if (user == null || user.rut() == null) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Sin sesion");
        }
        List<Payment> devueltos;
        if (user.isCreditor()) {
            List<Long> cartera = deudas.cartera(user.rut());
            devueltos = cartera.isEmpty() ? List.of()
                    : payments.findByStatusAndDebtIdInOrderByCreatedAtDesc(Payment.Status.refunded, cartera);
        } else if (user.isDebtor()) {
            devueltos = payments.findByDebtorRutOrderByCreatedAtDesc(user.rut()).stream()
                    .filter(p -> p.getStatus() == Payment.Status.refunded)
                    .toList();
        } else {
            throw new ApiException(HttpStatus.FORBIDDEN, "Solo la empresa o el deudor ven sus pagos devueltos");
        }
        return devueltos.stream().map(this::conSuDevolucion).toList();
    }

    /** El pago con su devolucion: la ultima anotada en su libro. */
    private PaymentResponse conSuDevolucion(Payment pago) {
        PaymentEvent devolucion = eventos.findByPaymentIdOrderByIdAsc(pago.getId()).stream()
                .filter(e -> e.getType() == PaymentEvent.Type.refunded)
                .reduce((primero, segundo) -> segundo)
                .orElse(null);
        if (devolucion == null) {
            return respuesta(pago);
        }
        String referencia = null;
        try {
            JsonNode libro = devolucion.getGatewayPayload() == null ? null : JSON.readTree(devolucion.getGatewayPayload());
            referencia = libro == null || libro.path("referencia").isMissingNode() ? null : libro.path("referencia").asText();
        } catch (JsonProcessingException e) {
            log.warn("Pago {}: el libro de la devolucion no se puede leer", pago.getId());
        }
        return respuesta(pago).conDevolucion(devolucion.getOccurredAt(), referencia);
    }

    /** El libro de un pago, para el panel y para auditar. */
    public HistoriaResponse historia(JwtPrincipal user, Long id) {
        visible(user, id);
        return new HistoriaResponse(eventos.findByPaymentIdOrderByIdAsc(id).stream()
                .map(PaymentEventResponse::from)
                .toList());
    }

    private Payment visible(JwtPrincipal user, Long id) {
        Payment pago = buscar(id);
        String suyo = user == null ? null
                : user.isCreditor() ? pago.getCreditorRut() : pago.getDebtorRut();
        if (!mismo(user == null ? null : user.rut(), suyo)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "No puedes ver este pago");
        }
        return pago;
    }

    // ------------------------------------------------------------------
    //  Confirmar
    // ------------------------------------------------------------------

    @Transactional
    public PaymentResponse confirmPublic(Long id, String sig) {
        Payment pago = conFirmaValida(id, sig);
        //  La confirmacion "a mano" es la de la pasarela simulada. Un pago real
        //  lo confirma solo su pasarela: si no, cualquiera con el enlace podria
        //  darlo por pagado sin pagar.
        if (cobraDeVerdad(pago)) {
            throw new ApiException(HttpStatus.CONFLICT,
                    "Este pago lo confirma " + nombre(pago) + ", al terminar de pagar alla");
        }
        return confirmar(pago, PaymentEvent.Source.portal, null, null, null);
    }

    // ------------------------------------------------------------------
    //  Khipu
    // ------------------------------------------------------------------

    /**
     * Le pregunta a la pasarela en que va el pago. Lo llama la pagina del
     * resultado, a la que la pasarela devuelve al deudor: ni Khipu ni Mercado
     * Pago dicen nada al devolverlo, asi que hay que preguntar. Un pago
     * simulado o ya cerrado se devuelve tal cual.
     */
    @Transactional
    public PaymentResponse verificar(Long id, String sig) {
        Payment pago = conFirmaValida(id, sig);
        if (cobraKhipu(pago)) {
            conciliar(pago);
        }
        if (cobraMercadoPago(pago)) {
            try {
                conciliarMercadoPago(pago);
            } catch (ApiException e) {
                //  Mercado Pago no respondio: el pago sigue abierto y se reintenta
                //  en la proxima consulta (la periodica o la de esta pagina).
                log.warn("No se pudo conciliar el pago {} con Mercado Pago: {}", pago.getId(), e.getMessage());
            }
        }
        return respuesta(pago);
    }

    /**
     * El deudor se arrepintio en Khipu y volvio por la {@code cancel_url}.
     * Antes de darlo por fallido se le pregunta a Khipu: si alcanzo a pagar,
     * el pago vale. Si no, se anula el cobro en Khipu: sin eso seguiria vivo
     * hasta vencer, y si el deudor volvia atras y pagaba en esa misma pagina,
     * Khipu recibia la plata con el pago ya fallido aca. Si Khipu no lo deja
     * anular, es que alguien lo pago en el intermedio: se vuelve a preguntar.
     */
    @Transactional
    public PaymentResponse cancelar(Long id, String sig) {
        Payment pago = conFirmaValida(id, sig);
        if (cobraKhipu(pago)) {
            conciliar(pago);
            if (pago.getStatus() == Payment.Status.created && !khipu.anular(pago.getGatewayTxnId())) {
                conciliar(pago);
            }
            if (pago.getStatus() == Payment.Status.created) {
                fallido(pago, null);
            }
        }
        return respuesta(pago);
    }

    /**
     * El aviso de Khipu: un pago se concilio. Solo llega si DataBridge tiene
     * una direccion publica ({@code KHIPU_URL_AVISOS}). No se aplica lo que
     * dice: se verifica su firma y se le pregunta a Khipu, asi que un aviso
     * falso o repetido no cobra nada.
     */
    @Transactional
    public void avisoDeKhipu(String cuerpo, String firma, String paymentId) {
        if (!firmaDeKhipu.valida(firma, cuerpo)) {
            log.info("firewall domain=PAYMENTS rule=KHIPU_SIGNATURE outcome=denied status=401");
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Firma de Khipu invalida");
        }
        if (paymentId == null || paymentId.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "El aviso no dice que pago es");
        }
        Payment pago = payments.findByGatewayAndGatewayTxnId(Payment.Gateway.khipu, paymentId.trim())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Khipu aviso un pago que no existe"));
        conciliar(pago);
    }

    /**
     * Los cobros abiertos de Khipu y de Mercado Pago: se le pregunta a la
     * pasarela por cada uno. Asi un pago se registra aunque el deudor cierre la
     * ventana antes de volver, y sin depender del aviso ni de la vuelta, que en
     * local no llegan (Mercado Pago ni siquiera devuelve a una direccion http).
     *
     * <p>El que paso el plazo sin pagarse se anula en la pasarela y queda
     * vencido. No si la pasarela lo esta verificando: el deudor ya pago, y
     * vencerlo dejaria la plata cobrada sin abonar. Devuelve cuantos se
     * cerraron.
     */
    @Transactional
    public int conciliarPendientes() {
        int cerrados = 0;
        if (khipu.real()) {
            cerrados += pendientes(Payment.Gateway.khipu, venceEn, this::conciliar,
                    pago -> khipu.anular(pago.getGatewayTxnId()));
        }
        if (mercadopago.real()) {
            //  La preferencia ya vence sola en Mercado Pago, a la misma hora.
            cerrados += pendientes(Payment.Gateway.mercadopago, venceEnMercadoPago, this::conciliarMercadoPago,
                    pago -> { });
        }
        return cerrados;
    }

    private int pendientes(Payment.Gateway pasarela, Duration plazo, Predicate<Payment> conciliador,
                           Consumer<Payment> anular) {
        int cerrados = 0;
        Instant limite = Instant.now().minus(plazo);
        for (Payment pago : payments.findByGatewayAndStatus(pasarela, Payment.Status.created)) {
            try {
                boolean enVerificacion = conciliador.test(pago);
                if (pago.getStatus() == Payment.Status.created && pago.getCreatedAt().isBefore(limite)
                        && !enVerificacion) {
                    //  Anulado, ya no se puede pagar. Si alcanzo a pagarse, la
                    //  segunda pregunta lo registra o lo encuentra verificando.
                    anular.accept(pago);
                    if (!conciliador.test(pago) && pago.getStatus() == Payment.Status.created) {
                        vencer(pago);
                    }
                }
            } catch (ApiException pasarelaNoResponde) {
                continue;
            }
            if (pago.getStatus() != Payment.Status.created) {
                cerrados++;
            }
        }
        return cerrados;
    }

    /**
     * Los cobros de Khipu y de Mercado Pago que se dieron por vencidos en el
     * ultimo dia: se vuelve a preguntar por ellos. Un intento empezado justo
     * antes de vencer puede terminar despues, y esa plata se cobro: se
     * registra. Devuelve cuantos aparecieron pagados.
     */
    @Transactional
    public int revisarVencidos() {
        int registrados = 0;
        if (khipu.real()) {
            registrados += vencidos(Payment.Gateway.khipu, venceEn, this::conciliar);
        }
        if (mercadopago.real()) {
            registrados += vencidos(Payment.Gateway.mercadopago, venceEnMercadoPago, this::conciliarMercadoPago);
        }
        return registrados;
    }

    private int vencidos(Payment.Gateway pasarela, Duration plazo, Predicate<Payment> conciliador) {
        int registrados = 0;
        Instant desde = Instant.now().minus(plazo).minus(REVISAR_VENCIDOS);
        for (Payment pago : payments.findByGatewayAndStatusAndCreatedAtAfter(pasarela, Payment.Status.expired, desde)) {
            try {
                conciliador.test(pago);
            } catch (ApiException pasarelaNoResponde) {
                continue;
            }
            if (pago.getStatus() != Payment.Status.expired) {
                registrados++;
                log.warn("El pago {} se pago en {} despues de vencido: quedo {}", pago.getId(), nombre(pago),
                        pago.getStatus());
            }
        }
        return registrados;
    }

    private void vencer(Payment pago) {
        pago.setStatus(Payment.Status.expired);
        payments.save(pago);
        eventos.save(PaymentEvent.de(pago.getId(), PaymentEvent.Type.expired, PaymentEvent.Source.webhook));
    }

    /**
     * Lo que dice Khipu, aplicado al pago. Pagado solo si Khipu lo concilio,
     * el monto es el que se cobro y la transaccion es la nuestra; terminado
     * sin plata, fallido; todavia en curso, no cambia nada.
     *
     * <p>Un pago vencido tambien se mira: si Khipu lo concilio despues, esa
     * plata se cobro y se registra.
     *
     * @return si Khipu lo esta verificando: el deudor ya pago y falta que
     *         Khipu lo confirme.
     */
    private boolean conciliar(Payment pago) {
        boolean abierto = pago.getStatus() == Payment.Status.created;
        if ((!abierto && pago.getStatus() != Payment.Status.expired) || pago.getGatewayTxnId() == null) {
            return false;
        }
        KhipuClient.Estado estado = khipu.estado(pago.getGatewayTxnId());
        String crudo = estado.crudo() == null ? null : estado.crudo().toString();
        boolean calza = estado.amount() != null && estado.amount().compareTo(BigDecimal.valueOf(pago.getAmountClp())) == 0
                && transaccion(pago).equals(estado.transactionId());
        if (estado.pagado() && calza) {
            confirmar(pago, PaymentEvent.Source.webhook, pago.getGatewayTxnId(), null, crudo);
        } else if (abierto && (estado.sinCobro() || estado.pagado())) {
            //  Pagado pero por otro monto u otra transaccion no se acepta: no
            //  es el cobro que se abrio.
            fallido(pago, crudo);
        }
        return "verifying".equals(estado.status());
    }

    // ------------------------------------------------------------------
    //  Webpay
    // ------------------------------------------------------------------

    /**
     * La pagina que lleva al deudor a Webpay: un formulario POST con el token,
     * que se envia solo. Asi lo pide Transbank, y asi el enlace del cobro se
     * abre igual que el de las otras pasarelas, en una ventana aparte.
     *
     * <p><b>El token se manda una sola vez.</b> En Webpay cada token sirve una
     * vez: mandarlo de nuevo (recargar, volver atras, abrir otra vez el enlace)
     * termina en el Error 21 de Transbank. La segunda vez la pagina explica que
     * Webpay ya se abrio y como seguir.
     */
    @Transactional
    public String paginaWebpay(Long id, String sig) {
        Payment pago = payments.paraCerrar(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Pago no encontrado"));
        exigirFirma(pago, sig);
        if (!cobraWebpay(pago) || pago.getGatewayTxnId() == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Ese pago no se hace en Webpay");
        }
        if (pago.getStatus() != Payment.Status.created) {
            return redireccion(enlaceDePago(pago));
        }
        if (pago.getRedirectedAt() != null) {
            return yaSeAbrioWebpay(pago);
        }
        pago.setRedirectedAt(Instant.now());
        payments.save(pago);
        return """
                <!doctype html>
                <html lang="es"><head><meta charset="utf-8"><title>Webpay</title></head>
                <body onload="document.forms[0].submit()">
                <form method="post" action="%s">
                <input type="hidden" name="token_ws" value="%s">
                <noscript><button type="submit">Ir a Webpay</button></noscript>
                </form>
                </body></html>
                """.formatted(html(webpay.paginaDePago()), html(pago.getGatewayTxnId()));
    }

    /** La segunda vez que se abre la pagina de un cobro: el token ya se uso. */
    private String yaSeAbrioWebpay(Payment pago) {
        String portal = enlaceDePago(pago);
        return """
                <!doctype html>
                <html lang="es"><head><meta charset="utf-8">
                <meta name="viewport" content="width=device-width, initial-scale=1">
                <title>Webpay ya se abrió</title>
                <style>body{font-family:system-ui,sans-serif;max-width:30rem;margin:3rem auto;padding:0 1rem;
                color:#1f2328;line-height:1.5}a{color:#3E7B4F;font-weight:600}</style></head>
                <body>
                <h1>Webpay ya se abrió para este pago</h1>
                <p>Por seguridad, Webpay deja usar cada pago una sola vez. Si su ventana sigue abierta,
                termina el pago ahí.</p>
                <p>Si la cerraste, vuelve al portal y toca <b>Pagar</b> otra vez: se abre un pago nuevo.</p>
                <p><a href="%s">Volver al portal</a></p>
                </body></html>
                """.formatted(html(portal));
    }

    /**
     * Donde vuelve el deudor desde Webpay. Devuelve a donde mandarlo: la
     * pagina del resultado, en el portal.
     *
     * <ul>
     *   <li>Solo {@code token_ws}: pago. Se confirma la transaccion con
     *       Transbank y se aprueba si esta AUTHORIZED con codigo 0, el monto es
     *       el cobrado y la orden es la de este pago.</li>
     *   <li>{@code TBK_TOKEN} (con o sin {@code token_ws}): anulo, o hubo un
     *       error en el formulario de Webpay. No se confirma nada.</li>
     *   <li>Solo {@code TBK_ORDEN_COMPRA} y {@code TBK_ID_SESION}: se le acabo
     *       el tiempo. La sesion tiene que ser la de ese cobro: la orden se
     *       adivina, la sesion no.</li>
     * </ul>
     *
     * <p>Volver dos veces con el mismo token no vuelve a confirmar: Transbank
     * lo rechazaria, y el pago ya quedo cerrado la primera vez.
     */
    @Transactional
    public String retornoWebpay(String tokenWs, String tbkToken, String ordenDeCompra, String sesion) {
        boolean anulado = tbkToken != null && !tbkToken.isBlank();
        if (!anulado && tokenWs != null && !tokenWs.isBlank()) {
            Payment pago = porToken(tokenWs);
            if (pago.getStatus() == Payment.Status.created) {
                confirmarEnWebpay(pago, tokenWs.trim());
            }
            return enlaceDePago(pago);
        }
        Payment pago = anulado ? porToken(tbkToken) : porOrden(ordenDeCompra);
        if (!anulado && !mismo(sesion, sesionWebpay(pago))) {
            log.warn("Vuelta de Webpay por tiempo para el pago {} con una sesion que no es la suya: no se aplica",
                    pago.getId());
            throw new ApiException(HttpStatus.BAD_REQUEST, "Webpay no dijo que pago era");
        }
        if (pago.getStatus() == Payment.Status.created) {
            fallido(pago, null);
        }
        return enlaceDePago(pago);
    }

    private void confirmarEnWebpay(Payment pago, String token) {
        WebpayCommitResponse respuesta;
        try {
            respuesta = webpay.commitTransaction(token);
        } catch (ApiException transbankNoResponde) {
            //  No se sabe si quedo confirmada: el pago sigue abierto y, si nadie
            //  lo cierra, vence. Marcarlo fallido podria pisar un pago que otra
            //  vuelta del mismo deudor si alcanzo a confirmar.
            return;
        }
        aplicarWebpay(pago, token, respuesta);
    }

    /**
     * Lo que respondio Transbank, aplicado al pago. Pagado solo si esta
     * AUTHORIZED con codigo 0, el monto es el cobrado y la orden es la de este
     * pago; si no, fallido. Un pago ya vencido no pasa a fallido: sigue vencido.
     */
    private void aplicarWebpay(Payment pago, String token, WebpayCommitResponse respuesta) {
        String crudo = comoJson(respuesta);
        boolean montoCalza = respuesta.amount() != null && respuesta.amount().equals(pago.getAmountClp());
        boolean ordenCalza = pago.getId().equals(idDeLaOrden(respuesta.buyOrder()));
        if (respuesta.isAuthorized() && montoCalza && ordenCalza) {
            confirmar(pago, PaymentEvent.Source.webhook, token, null, crudo);
        } else if (pago.getStatus() == Payment.Status.created) {
            fallido(pago, crudo);
        }
    }

    /**
     * Le pregunta a Transbank en que quedo el cobro, y lo aplica.
     *
     * <ul>
     *   <li>Pagado y sin confirmar ({@code INITIALIZED} con {@code vci}): el
     *       deudor pago y cerro la ventana antes de volver. Si el cobro sigue
     *       abierto se confirma desde aca, como habria hecho su vuelta.</li>
     *   <li>Sin pagar ({@code INITIALIZED} sin {@code vci}): nada todavia.</li>
     *   <li>Confirmado, fallido, reversado o anulado: se aplica.</li>
     * </ul>
     *
     * <p>Un cobro vencido no se confirma aunque se haya pagado: se vencio
     * porque el deudor abrio otro, y confirmarlo seria cobrarle dos veces. Sin
     * confirmar, Transbank lo reversa.
     *
     * @throws ApiException si Transbank no responde.
     */
    private void segunTransbank(Payment pago, boolean puedeConfirmar) {
        String token = pago.getGatewayTxnId();
        WebpayCommitResponse estado = webpay.estado(token);
        if ("INITIALIZED".equals(estado.status())) {
            boolean pagado = estado.vci() != null && !estado.vci().isBlank();
            if (pagado && puedeConfirmar && pago.getStatus() == Payment.Status.created) {
                confirmarEnWebpay(pago, token);
            }
            return;
        }
        aplicarWebpay(pago, token, estado);
    }

    /**
     * Los cobros de Webpay que hay que revisar: los abiertos o, con
     * {@code vencidos}, los que se vencieron en el ultimo dia. Cada uno se
     * revisa despues por separado, con {@link #conciliarWebpay}.
     */
    @Transactional(readOnly = true)
    public List<Long> webpayPorRevisar(boolean vencidos) {
        if (!webpay.real()) {
            return List.of();
        }
        List<Payment> lista = vencidos
                ? payments.findByGatewayAndStatusAndCreatedAtAfter(Payment.Gateway.webpay, Payment.Status.expired,
                        Instant.now().minus(webpay.plazoDePago()).minus(REVISAR_VENCIDOS))
                : payments.findByGatewayAndStatus(Payment.Gateway.webpay, Payment.Status.created);
        return lista.stream().map(Payment::getId).toList();
    }

    /**
     * Un cobro de Webpay, segun Transbank. Lo que registra el pago del deudor
     * que cerro la ventana sin volver, y lo que cierra un cobro abandonado:
     * pasado el plazo de Transbank sin pagarse, queda vencido.
     *
     * <p>Va en su propia transaccion y lo primero es bloquear la fila del pago:
     * si el deudor vuelve justo ahora, su vuelta espera y encuentra el pago ya
     * cerrado, en vez de confirmarlo dos veces.
     *
     * @return si el cobro quedo cerrado: pagado, fallido o vencido.
     */
    @Transactional
    public boolean conciliarWebpay(Long id) {
        Payment pago = payments.paraCerrar(id).orElse(null);
        if (pago == null || !cobraWebpay(pago) || pago.getGatewayTxnId() == null
                || (pago.getStatus() != Payment.Status.created && pago.getStatus() != Payment.Status.expired)) {
            return false;
        }
        Payment.Status antes = pago.getStatus();
        try {
            segunTransbank(pago, true);
        } catch (ApiException transbankNoResponde) {
            //  Se pregunta de nuevo en la proxima pasada. Si en un dia entero no
            //  contesta, el cobro no puede quedar abierto para siempre.
            if (antes == Payment.Status.created && vencio(pago, webpay.plazoDePago().plus(REVISAR_VENCIDOS))) {
                vencer(pago);
            }
            return pago.getStatus() != antes;
        }
        if (pago.getStatus() == Payment.Status.created && vencio(pago, webpay.plazoDePago())) {
            vencer(pago);
        }
        if (antes == Payment.Status.expired && pago.getStatus() != antes) {
            log.warn("El pago {} se pago en Webpay despues de vencido: quedo {}", pago.getId(), pago.getStatus());
        }
        return pago.getStatus() != antes;
    }

    private static boolean vencio(Payment pago, Duration plazo) {
        return pago.getCreatedAt() != null && pago.getCreatedAt().isBefore(Instant.now().minus(plazo));
    }

    // ------------------------------------------------------------------
    //  Mercado Pago
    // ------------------------------------------------------------------

    /**
     * Pregunta a Mercado Pago si el cobro de esta preferencia ya se pago.
     *
     * <p>Hace falta porque en local Mercado Pago no puede devolverse a un
     * {@code http://localhost} (descarta las back_urls que no son https) ni
     * avisar a la maquina del desarrollador. Mercado Pago guarda los pagos de
     * cada preferencia, asi que se pregunta ahi y no se espera a que el deudor
     * vuelva.</p>
     *
     * <p>Un intento rechazado no cierra el cobro: en Checkout Pro el deudor
     * puede reintentar con otra tarjeta sobre la misma preferencia, y si aca
     * quedara fallido, el pago que hiciera despues no se registraria. Si no lo
     * logra, la preferencia vence y aca el cobro queda vencido.</p>
     *
     * <p>El estado no se toma de la respuesta: se usa el id del pago para
     * preguntarle a {@code /v1/payments}, que es la fuente que tambien usa el
     * aviso. Asi un pago de otra preferencia no puede cerrar este pago.</p>
     *
     * <p>Un pago vencido tambien se mira: si Mercado Pago lo aprobo despues,
     * esa plata se cobro y se registra.</p>
     *
     * @return si el pago sigue en curso en Mercado Pago (pendiente o en
     *         revision): todavia se puede aprobar.
     * @throws ApiException si Mercado Pago no responde.
     */
    private boolean conciliarMercadoPago(Payment pago) {
        boolean abierto = pago.getStatus() == Payment.Status.created;
        if (!abierto && pago.getStatus() != Payment.Status.expired) {
            return false;
        }
        //  Al abrirse el cobro se guardo el id de la preferencia; ya confirmado
        //  queda el id del pago, y entonces no hay nada que conciliar.
        String referencia = pago.getGatewayTxnId();
        if (referencia == null || referencia.isBlank()) {
            return false;
        }
        MercadoPagoClient.EstadoPreferencia pref = mercadopago.consultarPreferencia(referencia.trim());
        if (pref == null || pref.pago() == null || pref.pago().id() == null) {
            return false; //  Todavia no se ha pagado nada sobre esta preferencia.
        }
        MercadoPagoClient.PagoInfo info = mercadopago.consultarPago(String.valueOf(pref.pago().id()));
        if (info == null) {
            return false;
        }
        String crudo = info.crudo() != null ? info.crudo().toString() : null;
        boolean esElMismo = correspondeMercadoPago(pago, info)
                && referencia.trim().equals(pref.id()) && pref.pago().id().equals(info.id());
        if (!esElMismo) {
            log.warn("La respuesta de Mercado Pago no corresponde al cobro {}: no se aplica", pago.getId());
            return false;
        }
        if (info.pagado()) {
            confirmar(pago, PaymentEvent.Source.webhook, String.valueOf(info.id()), null, crudo);
        }
        return info.status() != null && EN_CURSO_MERCADOPAGO.contains(info.status().toLowerCase(Locale.ROOT));
    }

    /**
     * Donde vuelve el deudor desde Mercado Pago tras pagar o cancelar.
     * La firma identifica el cobro propio; el estado se consulta contra la
     * preferencia guardada. Ningun parametro de estado del navegador autoriza
     * un abono. Los enlaces anteriores, sin firma, vuelven al portal y la
     * consulta periodica sigue conciliando sus cobros.
     */
    @Transactional
    public String retornoMercadoPago(Long id, String sig) {
        if (id == null || sig == null || sig.isBlank()) {
            return publicUrl + "/app";
        }
        Payment pago = conFirmaValida(id, sig);
        if (!cobraMercadoPago(pago)) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Ese pago no se hace en Mercado Pago");
        }
        try {
            conciliarMercadoPago(pago);
        } catch (ApiException noDisponible) {
            log.warn("No se pudo conciliar el retorno del cobro {}: se reintentara", pago.getId());
        }

        return enlaceDePago(pago);
    }

    /**
     * Procesa avisos asíncronos (Webhooks / IPN) de Mercado Pago.
     */
    @Transactional
    public void avisoMercadoPago(String topic, String idParam, String cuerpo) {
        if (!mercadopago.real()) {
            return;
        }
        String paymentId = idParam;
        if ((paymentId == null || paymentId.isBlank()) && cuerpo != null) {
            try {
                com.fasterxml.jackson.databind.JsonNode nodo = JSON.readTree(cuerpo);
                if (nodo == null) {
                    return;
                }
                if (nodo.hasNonNull("data") && nodo.get("data").hasNonNull("id")) {
                    paymentId = nodo.get("data").get("id").asText();
                } else if (nodo.hasNonNull("id")) {
                    paymentId = nodo.get("id").asText();
                }
            } catch (JsonProcessingException ignored) {
                return;
            }
        }
        if (paymentId != null && paymentId.trim().matches("[1-9][0-9]{0,18}")) {
            MercadoPagoClient.PagoInfo info = mercadopago.consultarPago(paymentId.trim());
            if (info == null || info.id() == null || !String.valueOf(info.id()).equals(paymentId.trim())
                    || info.externalReference() == null) {
                return;
            }
            Long id;
            try {
                id = Long.valueOf(info.externalReference().trim());
            } catch (NumberFormatException noEsUnCobro) {
                return;
            }
            Payment pago = payments.findById(id).orElse(null);
            if (pago != null && correspondeMercadoPago(pago, info)) {
                //  El aviso solo despierta la conciliacion de la preferencia
                //  persistida, igual que la consulta periodica y el retorno.
                conciliarMercadoPago(pago);
            }
        }
    }

    private boolean correspondeMercadoPago(Payment pago, MercadoPagoClient.PagoInfo info) {
        return cobraMercadoPago(pago) && info != null && info.id() != null && info.id() > 0
                && String.valueOf(pago.getId()).equals(info.externalReference() == null ? null
                        : info.externalReference().trim())
                && "CLP".equals(info.currencyId()) && pago.getAmountClp() != null
                && info.transactionAmount() != null
                && info.transactionAmount().compareTo(BigDecimal.valueOf(pago.getAmountClp())) == 0;
    }

    /** El pago de Webpay de ese token, con su fila bloqueada: la vuelta y la consulta no se cruzan. */
    private Payment porToken(String token) {
        return payments.paraCerrarPorToken(Payment.Gateway.webpay, token.trim())
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Webpay devolvio un pago que no existe"));
    }

    private Payment porOrden(String ordenDeCompra) {
        Long id = idDeLaOrden(ordenDeCompra);
        if (id == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Webpay no dijo que pago era");
        }
        Payment pago = payments.paraCerrar(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Webpay devolvio un pago que no existe"));
        if (pago.getGateway() != Payment.Gateway.webpay) {
            throw new ApiException(HttpStatus.NOT_FOUND, "Webpay devolvio un pago que no existe");
        }
        return pago;
    }

    /** El id del pago dentro de la orden de compra ({@code ORD41T12345}), o null. */
    private static Long idDeLaOrden(String ordenDeCompra) {
        Matcher m = ORDEN.matcher(ordenDeCompra == null ? "" : ordenDeCompra.trim());
        return m.matches() ? Long.valueOf(m.group(1)) : null;
    }

    private static String comoJson(Object respuesta) {
        try {
            return JSON.writeValueAsString(respuesta);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String redireccion(String url) {
        return """
                <!doctype html>
                <html lang="es"><head><meta charset="utf-8"><meta http-equiv="refresh" content="0;url=%s"></head>
                <body><a href="%s">Ver el resultado del pago</a></body></html>
                """.formatted(html(url), html(url));
    }

    private static String html(String texto) {
        return texto.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void fallido(Payment pago, String crudo) {
        pago.setStatus(Payment.Status.failed);
        payments.save(pago);
        eventos.save(PaymentEvent.de(pago.getId(), PaymentEvent.Type.failed, PaymentEvent.Source.webhook)
                .conRespuesta(crudo));
    }

    /**
     * El aviso de la pasarela.
     *
     * <p>Un webhook se reintenta: el mismo aviso puede llegar dos o tres
     * veces. La segunda no vuelve a cobrar, y la base lo garantiza con el
     * unico (gateway, gateway_txn_id) por si el codigo se equivoca.
     */
    @Transactional
    public PaymentResponse webhook(WebhookRequest aviso, String signatureHeader) {
        Long id = aviso.pago();
        if (id == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "El aviso no dice que pago es");
        }
        Payment pago = buscar(id);

        String sig = signatureHeader == null || signatureHeader.isBlank() ? aviso.signature() : signatureHeader;
        boolean firmaOk = verifier.matches(sig, String.valueOf(pago.getId()),
                pago.getAmount().toPlainString(), String.valueOf(pago.getDebtId()));

        if (!firmaOk) {
            log.info("firewall domain=PAYMENTS rule=WEBHOOK_SIGNATURE outcome=denied status=401");
            //  Un aviso con firma invalida no se aplica, pero se guarda:
            //  alguien mandando avisos falsos es algo que hay que poder ver.
            eventos.save(PaymentEvent.de(pago.getId(), PaymentEvent.Type.failed, PaymentEvent.Source.webhook)
                    .conFirma(false));
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Firma criptografica invalida");
        }
        if (cobraDeVerdad(pago)) {
            throw new ApiException(HttpStatus.CONFLICT, "Este pago lo confirma " + nombre(pago));
        }
        return confirmar(pago, PaymentEvent.Source.webhook, aviso.txnId(), true, null);
    }

    /** El monto en pesos, con el valor de la UF del dia en Chile si la deuda es en UF. */
    private void fijarPesos(Payment pago) {
        if (pago.getCurrency() == Payment.Currency.UF) {
            UfValue valor = uf.delDia(LocalDate.now(CHILE));
            pago.setUfValue(valor.getValue());
            pago.setAmountClp(uf.aPesos(pago.getAmount(), valor.getValue()));
        } else {
            pago.setAmountClp(pago.getAmount().longValue());
        }
    }

    private PaymentResponse confirmar(Payment pago, PaymentEvent.Source origen, String txnId, Boolean firmaOk,
                                      String respuestaDeLaPasarela) {
        //  Idempotencia: el pago ya cobrado se devuelve tal cual.
        if (pago.getStatus() == Payment.Status.paid || pago.getStatus() == Payment.Status.duplicated) {
            return respuesta(pago);
        }
        //  Solo los cobros abiertos antes de que se fijaran al abrir.
        if (pago.getAmountClp() == null) {
            fijarPesos(pago);
        }

        //  Si otro pago ya cubrio alguna de estas cuotas, el deudor pago dos
        //  veces: este no se abona (ms-debt no se entera) y queda duplicado,
        //  con su transaccion, para devolverlo en la pasarela.
        Payment anterior = pagoQueYaLasCubrio(pago);
        pago.setGatewayTxnId(txnId == null || txnId.isBlank() ? "int-" + pago.getId() : txnId.trim());
        if (anterior != null) {
            pago.setStatus(Payment.Status.duplicated);
        } else {
            pago.setStatus(Payment.Status.paid);
            pago.setPaidAt(Instant.now());
        }

        try {
            payments.save(pago);
            if (anterior != null) {
                eventos.save(PaymentEvent.de(pago.getId(), PaymentEvent.Type.duplicated, origen).conFirma(firmaOk)
                        .conRespuesta(respuestaDeLaPasarela));
                log.warn("Pago {} duplicado: sus cuotas ya las pago el {}. Hay que devolverlo en {} ({})",
                        pago.getId(), anterior.getId(), nombre(pago), pago.getGatewayTxnId());
                return respuesta(pago);
            }
            eventos.save(PaymentEvent.de(pago.getId(), PaymentEvent.Type.paid, origen).conFirma(firmaOk)
                    .conRespuesta(respuestaDeLaPasarela));
            //  El aviso queda encolado aqui, en la misma transaccion. Si
            //  ms-debt esta caido, el pago igual quedo guardado.
            if (avisos.findByPaymentId(pago.getId()).isEmpty()) {
                avisos.save(DebtNotification.para(pago.getId()));
            }
        } catch (DataIntegrityViolationException choque) {
            //  El unico de la pasarela salto: ese aviso ya se habia aplicado.
            throw new ApiException(HttpStatus.CONFLICT, "Ese pago de la pasarela ya estaba registrado");
        }
        return respuesta(pago);
    }

    /** Otro pago de la misma deuda, ya pagado, que cubre alguna de las cuotas de este. */
    private Payment pagoQueYaLasCubrio(Payment pago) {
        Set<Long> cuotas = pago.cuotas();
        if (cuotas.isEmpty()) {
            return null;
        }
        return payments.findByDebtIdAndStatus(pago.getDebtId(), Payment.Status.paid).stream()
                .filter(otro -> !otro.getId().equals(pago.getId()))
                .filter(otro -> !Collections.disjoint(otro.cuotas(), cuotas))
                .findFirst()
                .orElse(null);
    }

    // ------------------------------------------------------------------
    //  Auxiliares
    // ------------------------------------------------------------------

    private Payment buscar(Long id) {
        return payments.findById(id)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Pago no encontrado"));
    }

    private Payment conFirmaValida(Long id, String sig) {
        Payment pago = buscar(id);
        exigirFirma(pago, sig);
        return pago;
    }

    private void exigirFirma(Payment pago, String sig) {
        if (!verifier.matches(sig, String.valueOf(pago.getId()),
                pago.getAmount().toPlainString(), String.valueOf(pago.getDebtId()))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "Firma de checkout invalida");
        }
    }

    private static boolean mismo(String a, String b) {
        return a != null && b != null && a.equalsIgnoreCase(b);
    }

    private static Payment.Gateway pasarela(String valor) {
        try {
            return Payment.Gateway.valueOf((valor == null ? "" : valor.trim()).toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Pasarela no soportada (Mercado Pago, Khipu o Webpay)");
        }
    }
}
