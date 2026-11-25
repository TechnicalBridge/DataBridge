package com.tbridge.payments.client;

import com.tbridge.payments.exception.ApiError;
import com.tbridge.payments.exception.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.math.BigDecimal;
import java.util.List;

/**
 * Lo que ms-payments necesita saber de una deuda, preguntandoselo a ms-debt.
 *
 * <p><b>Por que existe.</b> Antes el monto a cobrar venia en el cuerpo de la
 * peticion, es decir, lo decidia el navegador. Quien supiera el id de una
 * deuda podia pagar un peso y darla por saldada. Ahora el monto y a quien se
 * le debe salen del servicio que manda sobre eso.
 *
 * <pre>
 *   GET /internal/debts/{id}?installmentIds=12&installmentIds=13   (opcional)
 *   X-Internal-Key: ...
 *   -> { debtId, creditorRut, debtorRut, currency, amount, installmentId, installmentIds, capital, interes,
 *        descuento }
 * </pre>
 */
@Component
public class DebtClient {

    /** Cuanto se debe y a quien, segun ms-debt. */
    public record DebtSnapshot(
            Long debtId,
            String creditorRut,
            String debtorRut,
            String currency,
            BigDecimal amount,
            Long installmentId,
            //  Las cuotas que cubre ese monto, de la que vence primero en adelante.
            List<Long> installmentIds,
            //  Lo que el monto tiene de capital y de mora, si la deuda genera intereses.
            BigDecimal capital,
            BigDecimal interes,
            //  La mora condonada por pronto pago: el monto ya viene sin ella.
            BigDecimal descuento
    ) {
        /** Una deuda sin descuento. */
        public DebtSnapshot(Long debtId, String creditorRut, String debtorRut, String currency, BigDecimal amount,
                            Long installmentId, List<Long> installmentIds, BigDecimal capital, BigDecimal interes) {
            this(debtId, creditorRut, debtorRut, currency, amount, installmentId, installmentIds, capital, interes,
                    null);
        }
    }

    private final RestClient rest;
    private final String internalKey;

    @Autowired
    public DebtClient(@Value("${app.debt-url}") String debtUrl,
                      @Value("${app.internal-key}") String internalKey) {
        this(debtUrl, internalKey, TiemposDePasarela.fabrica());
    }

    /** Con otros tiempos maximos: para probarlos sin esperar veinte segundos. */
    DebtClient(String debtUrl, String internalKey, ClientHttpRequestFactory fabrica) {
        this.rest = RestClient.builder().requestFactory(fabrica).baseUrl(debtUrl.replaceAll("/$", "")).build();
        this.internalKey = internalKey;
    }

    /**
     * La deuda —o las cuotas— que se van a pagar.
     *
     * <p>Si ms-debt no responde, el pago no se inicia. Es a proposito: cobrar
     * sin saber cuanto ni a quien es peor que no cobrar. Si ms-debt responde
     * con un error propio (la deuda no existe, no tiene saldo, las cuotas no
     * van en orden), se devuelve el mismo codigo con el mismo texto: esta
     * escrito para el deudor.
     */
    public DebtSnapshot obtener(Long debtId, List<Long> installmentIds) {
        return consultar(debtId, installmentIds, null);
    }

    /** RUT de la sesión: ms-debt autoriza antes de calcular o validar cuotas. */
    public DebtSnapshot obtenerParaCheckout(Long debtId, List<Long> installmentIds, String debtorRut) {
        if (debtorRut == null || debtorRut.isBlank()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "No puedes ver esta deuda");
        }
        return consultar(debtId, installmentIds, debtorRut);
    }

    private DebtSnapshot consultar(Long debtId, List<Long> installmentIds, String debtorRut) {
        boolean checkout = debtorRut != null;
        try {
            DebtSnapshot deuda = rest.get()
                    .uri(uri -> {
                        uri.path(checkout ? "/internal/debts/{id}/checkout" : "/internal/debts/{id}");
                        if (checkout) {
                            uri.queryParam("debtorRut", debtorRut);
                        }
                        if (installmentIds != null && !installmentIds.isEmpty()) {
                            uri.queryParam("installmentIds", installmentIds.toArray());
                        }
                        return uri.build(debtId);
                    })
                    .header("X-Internal-Key", internalKey)
                    .retrieve()
                    .body(DebtSnapshot.class);
            if (deuda == null) {
                throw new ApiException(checkout ? HttpStatus.FORBIDDEN : HttpStatus.NOT_FOUND,
                        checkout ? "No puedes ver esta deuda" : "Deuda no encontrada");
            }
            return deuda;
        } catch (HttpClientErrorException.Forbidden e) {
            if (checkout) {
                throw new ApiException(HttpStatus.FORBIDDEN, "No puedes ver esta deuda");
            }
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No se pudo consultar la deuda: el cobro no se inicia a ciegas");
        } catch (HttpClientErrorException.NotFound e) {
            if (checkout) {
                throw new ApiException(HttpStatus.FORBIDDEN, "No puedes ver esta deuda");
            }
            throw new ApiException(HttpStatus.NOT_FOUND, textoDe(e, "Deuda no encontrada"));
        } catch (HttpClientErrorException.Conflict e) {
            throw new ApiException(HttpStatus.CONFLICT, textoDe(e, "Esa deuda no tiene saldo por pagar"));
        } catch (HttpClientErrorException.BadRequest e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, textoDe(e, "Esas cuotas no se pueden pagar"));
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No se pudo consultar la deuda: el cobro no se inicia a ciegas");
        }
    }

    /**
     * Las deudas de la cartera de una empresa, tal como las ve en el portal: la
     * agencia ve las que entrego, no solo aquellas de las que es acreedora.
     */
    public List<Long> cartera(String rut) {
        try {
            Long[] ids = rest.get()
                    .uri("/internal/cartera/{rut}", rut)
                    .header("X-Internal-Key", internalKey)
                    .retrieve()
                    .body(Long[].class);
            return ids == null ? List.of() : List.of(ids);
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "No se pudo consultar la cartera");
        }
    }

    /** El texto del error de ms-debt, o uno por omision si no se puede leer. */
    private static String textoDe(HttpClientErrorException e, String porOmision) {
        try {
            ApiError error = e.getResponseBodyAs(ApiError.class);
            return error == null || error.error() == null || error.error().isBlank() ? porOmision : error.error();
        } catch (RuntimeException ilegible) {
            return porOmision;
        }
    }
}
