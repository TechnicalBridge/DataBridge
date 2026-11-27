package com.tbridge.debt.service;

import com.tbridge.debt.exception.ApiException;
import com.tbridge.debt.security.JwtPrincipal;
import com.tbridge.debt.dto.response.PagoResponse;
import com.tbridge.debt.model.Debt;
import com.tbridge.debt.model.DebtEvent;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * El comprobante de un pago: que se pago, de que deuda, por donde y cuando.
 *
 * <p>Existe por cada pago, no solo al final. El certificado dice que la deuda
 * quedo en cero; el comprobante es lo que el deudor muestra si le preguntan
 * por la cuota de octubre.
 */
@Service
@Transactional(readOnly = true)
public class ComprobanteService {

    private static final Map<String, String> PASARELAS = Map.of(
            "webpay", "Webpay", "mercadopago", "Mercado Pago", "khipu", "Khipu");

    private final HistorialService historial;

    public ComprobanteService(HistorialService historial) {
        this.historial = historial;
    }

    /**
     * Lo que dice el comprobante, ya escrito: lo mismo va en el PDF y en el
     * correo que le llega al deudor (#541).
     *
     * @param pagadoPor quien pago, con su RUT. El correo no lo repite: le llega a el.
     * @param filas     el detalle, sin {@code pagadoPor}.
     */
    public record Comprobante(Long folio, String monto, String nota, String acreedor, String pagadoPor,
                              List<String[]> filas) {
    }

    public byte[] generar(JwtPrincipal user, Long pagoId) {
        return pdf(armar(historial.pago(user, pagoId)));
    }

    /** El comprobante de un pago ya abonado, sin preguntar quien lo pide: para el correo al deudor. */
    public Comprobante armar(DebtEvent evento) {
        PagoResponse pago = historial.respuesta(evento);
        Debt deuda = evento.getDebt();

        String nota = null;
        if (pago.moneda() == Debt.Currency.UF && pago.montoClp() != null) {
            nota = "Equivale a " + DocumentoPdf.pesos(pago.montoClp())
                    + (pago.valorUf() == null ? "" : ", con la UF de ese día (" + DocumentoPdf.pesosConDecimales(pago.valorUf()) + ")");
        }

        List<String[]> filas = new ArrayList<>();
        filas.add(new String[]{"Acreedor", deuda.getCreditor().getTradeName() + ", RUT "
                + DocumentoPdf.rut(deuda.getCreditor().getRut())});
        filas.add(new String[]{"Deuda", deuda.getConcept() + ", contrato " + deuda.getExternalId()});
        filas.add(new String[]{"Qué se pagó", queSePago(pago)});
        if (pago.interes() != null && pago.interes().signum() > 0) {
            filas.add(new String[]{"Intereses por mora", DocumentoPdf.dinero(pago.interes(), pago.moneda())
                    + ", incluidos en el monto. El resto es capital: "
                    + DocumentoPdf.dinero(pago.monto().subtract(pago.interes()), pago.moneda())});
        }
        if (pago.descuento() != null && pago.descuento().signum() > 0) {
            filas.add(new String[]{"Descuento por pronto pago", DocumentoPdf.dinero(pago.descuento(), pago.moneda())
                    + " de intereses de mora condonados, por pagar toda la deuda durante la campaña"});
        }
        filas.add(new String[]{"Fecha", DocumentoPdf.fechaYHora(pago.pagadoEn())});
        if (pago.pasarela() != null) {
            filas.add(new String[]{"Medio de pago", PASARELAS.getOrDefault(pago.pasarela(), pago.pasarela())
                    + (pago.referencia() == null ? "" : ", operación " + pago.referencia())});
        }

        return new Comprobante(pago.id(), DocumentoPdf.dinero(pago.monto(), pago.moneda()), nota,
                deuda.getCreditor().getTradeName(),
                deuda.getDebtor().getFullName() + ", RUT " + DocumentoPdf.rut(deuda.getDebtor().getRut()), filas);
    }

    public byte[] pdf(Comprobante comprobante) {
        List<String[]> filas = new ArrayList<>();
        filas.add(new String[]{"Pagado por", comprobante.pagadoPor()});
        filas.addAll(comprobante.filas());
        try {
            return new DocumentoPdf()
                    .encabezado("Comprobante de pago", "Folio " + comprobante.folio())
                    .destacado("Monto pagado", comprobante.monto(), comprobante.nota())
                    .datos(filas.toArray(String[][]::new))
                    .pie("DataBridge emite este comprobante con el registro del pago. "
                            + comprobante.acreedor() + " ya fue avisado.")
                    .cerrar();
        } catch (Exception e) {
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, "No se pudo generar el comprobante");
        }
    }

    /**
     * "Cuotas 4 y 5 de 6", "Cuota 3 de 12", "El total de la deuda", o, si cubrio un mes informado despues
     * del convenio, "Fuera del convenio" y lo que haya pagado del plan.
     */
    static String queSePago(PagoResponse pago) {
        List<Integer> cuotas = pago.cuotas() == null ? List.of() : pago.cuotas();
        boolean fuera = pago.fueraDelConvenio() != null && pago.fueraDelConvenio() > 0;
        if (cuotas.isEmpty()) {
            return fuera ? "Fuera del convenio" : "Un abono a la deuda";
        }
        if (!fuera && pago.deCuotas() != null && pago.deCuotas() == 1) {
            return "El total de la deuda";
        }
        String de = pago.deCuotas() == null ? "" : " de " + pago.deCuotas();
        List<String> numeros = cuotas.stream().map(String::valueOf).toList();
        String plan = numeros.size() == 1 ? "cuota " + numeros.getFirst() + de
                : "cuotas " + String.join(", ", numeros.subList(0, numeros.size() - 1)) + " y " + numeros.getLast() + de;
        return fuera ? "Fuera del convenio y " + plan : Character.toUpperCase(plan.charAt(0)) + plan.substring(1);
    }
}
