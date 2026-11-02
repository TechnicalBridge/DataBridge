package com.tbridge.payments.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * El aviso que ms-payments le manda a ms-debt cuando un pago se concreta.
 *
 * <p>El deudor va por RUT —el correo cambia, el RUT no— y viaja lo cobrado en
 * pesos junto con el valor de la UF, para que ms-debt no tenga que volver a
 * convertir ni adivinar con que valor se hizo.
 *
 * <p>ms-payments lo emite y ms-debt lo recibe, y cada uno tiene su propia
 * copia de esta clase. Que esten de acuerdo en la forma y en el camino por
 * RabbitMQ lo fija pago-confirmado.json, del que cada servicio guarda una copia
 * en src/test/resources/contratos (la CI revisa que sean iguales): una prueba
 * en cada servicio (PagoConfirmadoContratoTest) compara su clase con su copia.
 */
public record PagoConfirmado(
        String tipo,
        Long paymentId,
        Long debtId,
        Long installmentId,
        String debtorRut,
        String creditorRut,
        BigDecimal amount,
        String currency,
        Long amountClp,
        BigDecimal ufValue,
        String gateway,
        String gatewayTxnId,
        Instant paidAt,
        //  Las cuotas que cubre el pago, tal como las cobro ms-debt, y cuanto de
        //  amount es interes de mora. Un aviso de antes no los trae.
        List<Long> installmentIds,
        BigDecimal interest,
        //  La mora que se condono por pronto pago (contrato §7.1). interest ya
        //  viene descontado: amount es el capital mas interest. Sin descuento, no viaja.
        BigDecimal discount
) {

    /** Un aviso sin el detalle de cuotas ni de intereses. */
    public PagoConfirmado(String tipo, Long paymentId, Long debtId, Long installmentId, String debtorRut,
                          String creditorRut, BigDecimal amount, String currency, Long amountClp, BigDecimal ufValue,
                          String gateway, String gatewayTxnId, Instant paidAt) {
        this(tipo, paymentId, debtId, installmentId, debtorRut, creditorRut, amount, currency, amountClp, ufValue,
                gateway, gatewayTxnId, paidAt, null, null, null);
    }

    /** Un aviso con sus cuotas y su interes, sin descuento. */
    public PagoConfirmado(String tipo, Long paymentId, Long debtId, Long installmentId, String debtorRut,
                          String creditorRut, BigDecimal amount, String currency, Long amountClp, BigDecimal ufValue,
                          String gateway, String gatewayTxnId, Instant paidAt, List<Long> installmentIds,
                          BigDecimal interest) {
        this(tipo, paymentId, debtId, installmentId, debtorRut, creditorRut, amount, currency, amountClp, ufValue,
                gateway, gatewayTxnId, paidAt, installmentIds, interest, null);
    }

    public static final String TIPO = "pago.confirmado";

    /*
     * Como viaja por RabbitMQ. Los dos servicios leen estas constantes: cuando
     * cada uno tenia las suyas, ms-payments publicaba con "pago.confirmado",
     * ms-debt escuchaba "pago.exitoso", y RabbitMQ descartaba cada pago en
     * silencio porque no calzaba con ninguna cola.
     */
    public static final String EXCHANGE = "tbridge.pagos";
    public static final String ROUTING_KEY = "pago.confirmado";
    public static final String QUEUE = "ms-debt.pagos-confirmados";
}
