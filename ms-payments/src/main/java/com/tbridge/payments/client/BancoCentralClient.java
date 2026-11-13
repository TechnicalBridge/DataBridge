package com.tbridge.payments.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.payments.model.UfValue;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * El valor de la UF desde la fuente oficial: la Base de Datos Estadisticos del
 * Banco Central de Chile (decision I10 del contrato).
 *
 * <p>La UF la calcula el Banco Central y la publica por adelantado: hacia el
 * dia 9 de cada mes quedan fijados los valores hasta el 9 del mes siguiente.
 * Por eso no hay "hora a la que se fija": se pide un rango que llega al mes
 * siguiente y se guarda lo que ya este publicado.
 *
 * <p>La API pide usuario y clave, que se obtienen registrandose en
 * si3.bcentral.cl. Sin ellos esta clase queda apagada y la UF se carga a mano
 * con {@code POST /internal/uf}.
 */
@Component
public class BancoCentralClient {

    /** Unidad de fomento, diaria. */
    static final String SERIE = "F073.UFF.PRE.Z.D";
    private static final DateTimeFormatter FECHA_BCCH = DateTimeFormatter.ofPattern("dd-MM-yyyy");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ObjectMapper json;
    private final String url;
    private final String usuario;
    private final String clave;

    public BancoCentralClient(ObjectMapper json,
                          @Value("${app.uf.bcentral-url}") String url,
                          @Value("${app.uf.bcentral-user:}") String usuario,
                          @Value("${app.uf.bcentral-pass:}") String clave) {
        this.json = json;
        this.url = url;
        this.usuario = usuario;
        this.clave = clave;
    }

    public boolean configurado() {
        return !usuario.isBlank() && !clave.isBlank();
    }

    public List<UfValue> obtener(LocalDate desde, LocalDate hasta) throws IOException, InterruptedException {
        String consulta = url
                + "?user=" + URLEncoder.encode(usuario, StandardCharsets.UTF_8)
                + "&pass=" + URLEncoder.encode(clave, StandardCharsets.UTF_8)
                + "&firstdate=" + desde + "&lastdate=" + hasta
                + "&timeseries=" + SERIE + "&function=GetSeries";
        HttpRequest peticion = HttpRequest.newBuilder(URI.create(consulta))
                .timeout(Duration.ofSeconds(20)).GET().build();
        HttpResponse<String> respuesta = http.send(peticion, HttpResponse.BodyHandlers.ofString());
        if (respuesta.statusCode() != 200) {
            throw new IOException("El Banco Central respondió HTTP " + respuesta.statusCode());
        }
        return leer(respuesta.body(), json);
    }

    /**
     * Lee la respuesta de GetSeries.
     *
     * <p>{@code Codigo} distinto de 0 es un error del servicio (credenciales,
     * serie). Los dias sin dato vienen con {@code statusCode} distinto de
     * {@code OK} y valor {@code NaN}, y se saltan: guardar un cero seria peor
     * que no guardar nada.
     */
    static List<UfValue> leer(String cuerpo, ObjectMapper json) throws IOException {
        JsonNode raiz = json.readTree(cuerpo);
        int codigo = raiz.path("Codigo").asInt(-1);
        if (codigo != 0) {
            throw new IOException("El Banco Central respondió código " + codigo + ": "
                    + raiz.path("Descripcion").asText("sin descripcion"));
        }
        List<UfValue> valores = new ArrayList<>();
        for (JsonNode obs : raiz.path("Series").path("Obs")) {
            String valor = obs.path("value").asText("");
            if (!"OK".equalsIgnoreCase(obs.path("statusCode").asText("")) || valor.isBlank()
                    || "NaN".equalsIgnoreCase(valor)) {
                continue;
            }
            LocalDate dia = LocalDate.parse(obs.path("indexDateString").asText(), FECHA_BCCH);
            valores.add(new UfValue(dia, new BigDecimal(valor).setScale(2, RoundingMode.HALF_UP), "bcentral"));
        }
        return valores;
    }
}
