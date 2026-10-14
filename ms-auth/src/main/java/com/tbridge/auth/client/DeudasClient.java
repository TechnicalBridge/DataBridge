package com.tbridge.auth.client;

import com.tbridge.auth.exception.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.Optional;

/**
 * Lo que ms-auth le pregunta a ms-debt: el correo que registro el acreedor
 * para un RUT, y si una empresa existe.
 *
 * <p>El enlace de acceso de un deudor va solo a su correo registrado. Si
 * ms-debt no responde, no hay correo: no sale ningun enlace. Mejor que el
 * deudor espere a que vuelva, que mandarle su acceso a cualquiera.
 */
@Component
public class DeudasClient {

    private static final Logger log = LoggerFactory.getLogger(DeudasClient.class);

    private final RestClient rest;
    private final String internalKey;

    @Autowired
    public DeudasClient(@Value("${app.debt-url}") String debtUrl, @Value("${app.internal-key}") String internalKey) {
        this(RestClient.builder().requestFactory(tiempos()), debtUrl, internalKey);
    }

    /** Con otro constructor de RestClient: para probarlo sin un ms-debt de verdad. */
    DeudasClient(RestClient.Builder builder, String debtUrl, String internalKey) {
        this.rest = builder.baseUrl(debtUrl.replaceAll("/$", "")).build();
        this.internalKey = internalKey;
    }

    private static SimpleClientHttpRequestFactory tiempos() {
        SimpleClientHttpRequestFactory fabrica = new SimpleClientHttpRequestFactory();
        fabrica.setConnectTimeout(2_000);
        fabrica.setReadTimeout(3_000);
        return fabrica;
    }

    /** El correo registrado para ese RUT. Vacio si no hay, o si ms-debt no responde. */
    public Optional<String> correoDelDeudor(String rut) {
        try {
            Correo respuesta = rest.get().uri("/internal/deudores/{rut}/correo", rut)
                    .header("X-Internal-Key", internalKey)
                    .retrieve()
                    .body(Correo.class);
            return Optional.ofNullable(respuesta).map(Correo::correo).filter(c -> c.contains("@"));
        } catch (HttpClientErrorException.NotFound sinCorreo) {
            return Optional.empty();
        } catch (RestClientException fallo) {
            log.warn("ms-debt no respondio por el correo de un deudor: {}", fallo.getMessage());
            return Optional.empty();
        }
    }

    /**
     * La empresa con ese RUT, tal como la conoce ms-debt. Vacio si no existe.
     *
     * <p>Aca no se calla la falla: antes de darle una persona a una empresa hay
     * que saber que existe, y si ms-debt no responde no se sabe. Mejor que el
     * admin reintente, que dejar a alguien colgando de una empresa que no esta.
     */
    public Optional<Empresa> empresa(String rut) {
        try {
            return Optional.ofNullable(rest.get().uri("/internal/organizaciones/{rut}", rut)
                    .header("X-Internal-Key", internalKey)
                    .retrieve()
                    .body(Empresa.class));
        } catch (HttpClientErrorException.NotFound noExiste) {
            return Optional.empty();
        } catch (RestClientException fallo) {
            log.warn("ms-debt no respondio por una empresa: {}", fallo.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "No se pudo comprobar la empresa: el servicio de deudas no responde. Intenta de nuevo.");
        }
    }

    /** Una empresa registrada en ms-debt: su RUT, como se la conoce y si puede operar. */
    public record Empresa(String rut, String nombre, boolean activa) {}

    record Correo(String correo) {}
}
