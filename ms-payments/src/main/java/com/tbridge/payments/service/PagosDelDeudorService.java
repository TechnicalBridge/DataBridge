package com.tbridge.payments.service;

import com.tbridge.payments.dto.response.PagoDeDeudorResponse;
import com.tbridge.payments.exception.ApiException;
import com.tbridge.payments.repository.PaymentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Los pagos de un deudor, para su ficha en el panel (#434). La ficha la abre
 * ms-debt, que la anota en la bitacora; aqui solo se leen sus pagos.
 */
@Service
public class PagosDelDeudorService {

    private static final Pattern RUT = Pattern.compile("\\d{1,8}-[\\dK]");
    private final PaymentRepository pagos;

    public PagosDelDeudorService(PaymentRepository pagos) {
        this.pagos = pagos;
    }

    /** Los ultimos 100, el mas reciente primero. Sin pagos, la lista va vacia. */
    @Transactional(readOnly = true)
    public List<PagoDeDeudorResponse> pagos(String rut) {
        String normalizado = normalizar(rut);
        if (!RUT.matcher(normalizado).matches()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "RUT no valido");
        }
        return pagos.findTop100ByDebtorRutOrderByCreatedAtDesc(normalizado).stream()
                .map(PagoDeDeudorResponse::de).toList();
    }

    /** '16.482.337-7', '164823377' y '16482337-7' quedan iguales, como en ms-debt. */
    static String normalizar(String crudo) {
        String limpio = (crudo == null ? "" : crudo).trim().toUpperCase(Locale.ROOT).replace(".", "").replace(" ", "");
        if (!limpio.contains("-") && limpio.length() > 1) {
            limpio = limpio.substring(0, limpio.length() - 1) + "-" + limpio.charAt(limpio.length() - 1);
        }
        return limpio;
    }
}
