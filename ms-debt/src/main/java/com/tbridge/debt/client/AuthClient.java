package com.tbridge.debt.client;

import com.tbridge.debt.exception.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Lo que ms-debt le pide a ms-auth: emitir el codigo de acceso de un deudor, y
 * mandarle el comprobante de un pago (#541).
 *
 * <p>ms-auth genera el codigo y lo manda al correo. La respuesta trae el
 * codigo, pero aca se lee solo cuando vence: el codigo nunca llega a quien lo
 * pidio. Si el personal de la empresa pudiera verlo, podria entrar como el
 * deudor.
 *
 * <p>Si el correo no sale, ms-auth responde {@code 502} y aca eso es un
 * {@link CorreoNoSalio}: quien llamo decide que hacer, pero nunca lo anota
 * como un contacto.
 */
@Component
public class AuthClient {

    /**
     * Lo que se le pide a ms-auth. Con {@code vence}, el correo es el
     * recordatorio de una cuota que vence ese dia; sin el, es el primer aviso.
     */
    public record PedidoDeCodigo(String rut, List<String> canales, String correo, String acreedor, String paraQue,
                                 LocalDate vence) {

        public PedidoDeCodigo(String rut, List<String> canales, String correo, String acreedor, String paraQue) {
            this(rut, canales, correo, acreedor, paraQue, null);
        }
    }

    /** Lo unico que se lee de la respuesta. */
    public record CodigoEmitido(Instant expiraEn) {}

    /**
     * El comprobante de un pago para el correo del deudor (#541): lo mismo
     * que dice el PDF, ya escrito, y los PDF. {@code queda} es lo que falta
     * por pagar; si la deuda quedo pagada, no va.
     */
    public record PedidoDeComprobante(String correo, String folio, String monto, String nota, String acreedor,
                                      List<Fila> filas, boolean saldada, String queda, List<Adjunto> adjuntos) {}

    public record Fila(String nombre, String valor) {}

    /** Un PDF. El contenido va en base64, como escribe Jackson un {@code byte[]}. */
    public record Adjunto(String nombre, byte[] contenido) {}

    /** Lo que responde ms-auth cuando el correo no salio. */
    record CorreoNoEnviado(String error, String causa) {}

    private final RestClient rest;
    private final String internalKey;
    private final RestClient contactoRest;

    public AuthClient(@Value("${app.auth-url}") String authUrl,
                      @Value("${app.internal-key}") String internalKey) {
        this.rest = RestClient.builder().baseUrl(authUrl.replaceAll("/$", "")).build();
        this.internalKey = internalKey;
        var timeouts = new org.springframework.http.client.SimpleClientHttpRequestFactory();
        timeouts.setConnectTimeout(2_000);
        timeouts.setReadTimeout(15_000);
        this.contactoRest = RestClient.builder().baseUrl(authUrl.replaceAll("/$", "")).requestFactory(timeouts).build();
    }

    public CodigoEmitido emitirCodigo(PedidoDeCodigo pedido) {
        try {
            return rest.post()
                    .uri("/internal/codigos")
                    .header("X-Internal-Key", internalKey)
                    .body(pedido)
                    .retrieve()
                    .body(CodigoEmitido.class);
        } catch (HttpServerErrorException.BadGateway noSalio) {
            CorreoNoEnviado cuerpo = leer(noSalio);
            throw new CorreoNoSalio(cuerpo == null ? null : cuerpo.error(),
                    cuerpo != null && "direccion".equals(cuerpo.causa()));
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No se pudo emitir el codigo: el servicio de acceso no responde");
        }
    }

    /**
     * Le pide a ms-auth el correo del comprobante. Espera a saber si salio.
     *
     * @throws CorreoNoSalio si el servidor de correo no lo acepto.
     */
    public void enviarComprobante(PedidoDeComprobante pedido) {
        try {
            rest.post()
                    .uri("/internal/correo/comprobante")
                    .header("X-Internal-Key", internalKey)
                    .body(pedido)
                    .retrieve()
                    .toBodilessEntity();
        } catch (HttpServerErrorException.BadGateway noSalio) {
            CorreoNoEnviado cuerpo = leer(noSalio);
            throw new CorreoNoSalio(cuerpo == null ? null : cuerpo.error(),
                    cuerpo != null && "direccion".equals(cuerpo.causa()));
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No se pudo mandar el comprobante: el servicio de acceso no responde");
        }
    }

    private record Verificacion(String correo, String codigo) {}
    private record Aviso(String eventoId, String correo, String codigo) {}
    private record Sesiones(String rut) {}
    public void verificarCorreo(String correo, String codigo) {
        enviarContacto("/internal/contacto/verificacion", new Verificacion(correo, codigo));
    }
    /** El aviso al correo anterior, con el codigo para deshacer el cambio (#471). */
    public void avisarCambio(String eventoId, String correo, String codigoParaDeshacer) {
        enviarContacto("/internal/contacto/aviso", new Aviso(eventoId, correo, codigoParaDeshacer));
    }
    /** Cierra todas las sesiones del deudor en ms-auth, cuando deshace un cambio que no pidio (#471). */
    public void revocarSesionesDeDeudor(String rut) {
        enviarContacto("/internal/contacto/sesiones", new Sesiones(rut));
    }
    private void enviarContacto(String ruta, Object solicitud) {
        try {
            contactoRest.post().uri(ruta).header("X-Internal-Key", internalKey).body(solicitud).retrieve().toBodilessEntity();
        } catch (RestClientException fallo) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "El servicio de correo no está disponible");
        }
    }

    private static CorreoNoEnviado leer(HttpServerErrorException respuesta) {
        try {
            return respuesta.getResponseBodyAs(CorreoNoEnviado.class);
        } catch (RuntimeException ilegible) {
            return null;
        }
    }
}
