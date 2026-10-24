package com.tbridge.payments.client;

import com.tbridge.payments.exception.ApiException;
import com.tbridge.payments.dto.gateway.WebpayCommitResponse;
import com.tbridge.payments.dto.gateway.WebpayCreateRequest;
import com.tbridge.payments.dto.gateway.WebpayCreateResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Duration;
import java.util.Locale;

/**
 * Cliente REST para la API de Transbank Webpay Plus (v1.2).
 *
 * <pre>
 *   POST /rswebpaytransaction/api/webpay/v1.2/transactions          crea la transaccion -> {token, url}
 *   PUT  /rswebpaytransaction/api/webpay/v1.2/transactions/{token}  la confirma         -> {status, response_code, ...}
 *   GET  /rswebpaytransaction/api/webpay/v1.2/transactions/{token}  en que esta          -> lo mismo, sin confirmar
 *   Tbk-Api-Key-Id / Tbk-Api-Key-Secret                              el codigo de comercio y su llave
 * </pre>
 *
 * <p>Entre crear y confirmar el deudor paga en la pagina de Webpay: se le manda
 * ahi con un formulario POST que lleva el {@code token_ws}, y Webpay lo devuelve
 * a la {@code return_url}. Un pago esta aprobado solo si la confirmacion
 * responde {@code status} AUTHORIZED y {@code response_code} 0.
 *
 * <p>Lo que dice la consulta, comprobado contra el ambiente de integracion:
 *
 * <ul>
 *   <li>creada, abierta o sin pagar: {@code INITIALIZED}, sin {@code vci};</li>
 *   <li><b>pagada y sin confirmar:</b> {@code INITIALIZED}, ya con {@code vci}
 *       y tipo de pago. Se puede confirmar desde aca aunque el deudor no
 *       vuelva;</li>
 *   <li>confirmada: {@code AUTHORIZED} y codigo 0. Confirmar otra vez responde
 *       lo mismo.</li>
 * </ul>
 *
 * <p>Por omision usa el ambiente de integracion (TEST), con el codigo de
 * comercio y la llave que Transbank publica para que cualquiera pruebe sin
 * registrarse. Con {@code TRANSBANK_ENVIRONMENT=SIMULADA}, Webpay vuelve a la
 * pasarela simulada, sin internet.
 */
@Component
public class WebpayClient {

    private static final Logger log = LoggerFactory.getLogger(WebpayClient.class);
    private static final String TRANSACCIONES = "/rswebpaytransaction/api/webpay/v1.2/transactions";

    /** El token dura 5 minutos y el formulario 10 en integracion: despues no se puede pagar. */
    static final Duration PLAZO_INTEGRACION = Duration.ofMinutes(15);

    /** En produccion el formulario dura 4 minutos: 5 de token mas 4. */
    static final Duration PLAZO_PRODUCCION = Duration.ofMinutes(9);

    private final RestClient rest;
    private final String base;
    private final String commerceCode;
    private final String apiKey;
    private final boolean real;
    private final Duration plazo;

    @Autowired
    public WebpayClient(
            @Value("${app.transbank.url:https://webpay3gint.transbank.cl}") String apiUrl,
            @Value("${app.transbank.commerce-code:597055555532}") String commerceCode,
            @Value("${app.transbank.api-key:579B532A7440BB0C9079DED94D31EA1615BACEB56610332264630D42D0A36B1C}") String apiKey,
            @Value("${app.transbank.environment:TEST}") String environment,
            @Value("${app.transbank.vence-en:}") String venceEn
    ) {
        this(apiUrl, commerceCode, apiKey, environment, venceEn, TiemposDePasarela.fabrica());
    }

    public WebpayClient(String apiUrl, String commerceCode, String apiKey, String environment) {
        this(apiUrl, commerceCode, apiKey, environment, "", TiemposDePasarela.fabrica());
    }

    /** Con otros tiempos maximos: para probarlos sin esperar veinte segundos. */
    WebpayClient(String apiUrl, String commerceCode, String apiKey, String environment, String venceEn,
                 ClientHttpRequestFactory fabrica) {
        this.base = apiUrl.replaceAll("/$", "");
        this.rest = RestClient.builder().baseUrl(base).requestFactory(fabrica).build();
        this.commerceCode = commerceCode;
        this.apiKey = apiKey;
        this.real = !"SIMULADA".equals(environment.trim().toUpperCase(Locale.ROOT));
        this.plazo = venceEn == null || venceEn.isBlank()
                ? (base.contains("webpay3g.transbank.cl") ? PLAZO_PRODUCCION : PLAZO_INTEGRACION)
                : DurationStyle.detectAndParse(venceEn.trim());
    }

    /** Si Webpay cobra de verdad (TEST o produccion) o queda la pasarela simulada. */
    public boolean real() {
        return real;
    }

    /** Si es la de produccion de Transbank, y no la de integracion, donde se paga con tarjetas de prueba. */
    public boolean produccion() {
        return base.contains("webpay3g.transbank.cl");
    }

    /**
     * Cuanto vale un cobro de Webpay desde que se abre: lo que tarda Transbank
     * en darlo por muerto. En integracion, 15 minutos; en produccion, 9.
     * {@code TRANSBANK_VENCE_EN} lo cambia.
     */
    public Duration plazoDePago() {
        return plazo;
    }

    /** La pagina de Webpay a la que se lleva al deudor con el token, por POST. */
    public String paginaDePago() {
        return base + "/webpayserver/initTransaction";
    }

    /**
     * Inicia una transaccion en Webpay Plus. Cobra en pesos enteros: una deuda
     * en UF ya llega convertida, con la UF del dia en que se abrio el cobro.
     */
    public WebpayCreateResponse createTransaction(String buyOrder, String sessionId, long amount, String returnUrl) {
        try {
            log.info("Iniciando transaccion Webpay: buyOrder={}, amount={}", buyOrder, amount);
            WebpayCreateResponse response = rest.post()
                    .uri(TRANSACCIONES)
                    .contentType(MediaType.APPLICATION_JSON)
                    .header("Tbk-Api-Key-Id", commerceCode)
                    .header("Tbk-Api-Key-Secret", apiKey)
                    .body(new WebpayCreateRequest(buyOrder, sessionId, amount, returnUrl))
                    .retrieve()
                    .body(WebpayCreateResponse.class);

            if (response == null || response.token() == null || response.url() == null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Webpay no devolvio la transaccion");
            }
            return response;
        } catch (HttpClientErrorException e) {
            log.error("Transbank rechazo la transaccion {} ({}): {}", buyOrder, e.getStatusCode(), e.getResponseBodyAsString());
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Webpay no responde en este momento. Prueba de nuevo, o paga con otro medio.");
        } catch (RestClientException e) {
            log.error("No fue posible conectar con Transbank Webpay: {}", e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY,
                    "Webpay no responde en este momento. Prueba de nuevo, o paga con otro medio.");
        }
    }

    /**
     * Confirma la transaccion: es lo que la cierra en Transbank. Sin esta
     * llamada el cargo no queda hecho, aunque el deudor haya visto "aprobado".
     * Repetirla responde lo mismo, no cobra dos veces.
     */
    public WebpayCommitResponse commitTransaction(String token) {
        WebpayCommitResponse response = llamar("confirmar", rest.put()
                .uri(TRANSACCIONES + "/{token}", token)
                .contentType(MediaType.APPLICATION_JSON));
        log.info("Confirmacion Webpay: buyOrder={}, status={}, responseCode={}",
                response.buyOrder(), response.status(), response.responseCode());
        return response;
    }

    /**
     * En que esta la transaccion, sin confirmarla. Transbank la responde hasta
     * siete dias despues de creada. Es lo que permite saber que el deudor pago
     * aunque haya cerrado la ventana antes de volver.
     */
    public WebpayCommitResponse estado(String token) {
        return llamar("consultar", rest.get().uri(TRANSACCIONES + "/{token}", token));
    }

    private WebpayCommitResponse llamar(String que, RestClient.RequestHeadersSpec<?> pedido) {
        try {
            WebpayCommitResponse response = pedido
                    .header("Tbk-Api-Key-Id", commerceCode)
                    .header("Tbk-Api-Key-Secret", apiKey)
                    .retrieve()
                    .body(WebpayCommitResponse.class);
            if (response == null) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, "Webpay no respondio");
            }
            return response;
        } catch (HttpClientErrorException e) {
            log.error("Transbank no dejo {} la transaccion ({}): {}", que, e.getStatusCode(), e.getResponseBodyAsString());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Webpay no confirmo la transaccion");
        } catch (RestClientException e) {
            log.error("No fue posible {} la transaccion con Transbank Webpay: {}", que, e.getMessage());
            throw new ApiException(HttpStatus.BAD_GATEWAY, "Webpay no confirmo la transaccion");
        }
    }
}
