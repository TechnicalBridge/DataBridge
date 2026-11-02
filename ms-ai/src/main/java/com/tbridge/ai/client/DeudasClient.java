package com.tbridge.ai.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tbridge.ai.exception.DeudasException;
import static com.tbridge.ai.exception.DeudasException.Categoria.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.*;
import java.util.*;

@Component
public class DeudasClient {
    private static final ParameterizedTypeReference<Map<String,Object>> OBJETO = new ParameterizedTypeReference<>() {};
    private final RestClient rest;
    @Autowired
    public DeudasClient(@Value("${app.debt-url}") String url) {
        this(RestClient.builder().requestFactory(tiempos()), url);
    }
    DeudasClient(RestClient.Builder builder, String url) {
        rest = builder.baseUrl(url.replaceAll("/+$", "")).build();
    }
    private static SimpleClientHttpRequestFactory tiempos() {
        var factory = new SimpleClientHttpRequestFactory() {
            @Override protected void prepareConnection(java.net.HttpURLConnection connection, String method)
                    throws java.io.IOException {
                super.prepareConnection(connection, method);
                connection.setInstanceFollowRedirects(false);
            }
        };
        factory.setConnectTimeout(2000); factory.setReadTimeout(8000);
        return factory;
    }
    public List<Map<String,Object>> deudasDelDeudor(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ") || authorization.substring(7).isBlank())
            throw new DeudasException(NO_AUTENTICADO);
        try {
            var response = rest.get().uri("/api/debts").header(HttpHeaders.AUTHORIZATION, authorization)
                    .retrieve().toEntity(OBJETO);
            if (response.getStatusCode().value() != 200) throw new DeudasException(PROTOCOLO);
            return deudasDe(response.getBody());
        } catch (RestClientResponseException ex) {
            int status = ex.getStatusCode().value();
            if (status == 401) throw new DeudasException(NO_AUTENTICADO);
            if (status == 403) throw new DeudasException(NO_AUTORIZADO);
            if (status >= 500) throw new DeudasException(NO_DISPONIBLE);
            if (status == 404 && identidadAusente(ex.getResponseBodyAsString()))
                throw new DeudasException(DEUDOR_NO_REGISTRADO);
            throw new DeudasException(PROTOCOLO);
        } catch (ResourceAccessException ex) {
            throw new DeudasException(NO_DISPONIBLE);
        } catch (RestClientException ex) {
            throw new DeudasException(PROTOCOLO);
        }
    }
    private static boolean identidadAusente(String body) {
        try {
            var json = new ObjectMapper().readTree(body);
            return json != null && json.isObject() && json.path("error").isTextual()
                    && "No hay deudas a tu nombre".equals(json.path("error").textValue());
        } catch (Exception ignored) { return false; }
    }
    static List<Map<String,Object>> deudasDe(Map<String,Object> body) {
        if (body == null) throw new DeudasException(PROTOCOLO);
        if (!body.containsKey("_embedded")) {
            if (body.get("_links") instanceof Map<?,?> links && links.get("self") instanceof Map<?,?> self
                    && self.get("href") instanceof String href && !href.isBlank()) return List.of();
            throw new DeudasException(PROTOCOLO);
        }
        if (!(body.get("_embedded") instanceof Map<?,?> embedded)
                || !(embedded.get("debts") instanceof List<?> rows)) throw new DeudasException(PROTOCOLO);
        List<Map<String,Object>> result = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof Map<?,?> debt)) throw new DeudasException(PROTOCOLO);
            Map<String,Object> projected = new HashMap<>();
            for (String key : List.of("acreedor", "concepto", "estado", "moneda")) {
                if (!(debt.get(key) instanceof String text) || text.isBlank()) throw new DeudasException(PROTOCOLO);
                projected.put(key, text);
            }
            if (!Set.of("open", "repacted", "paid", "disputed", "withdrawn").contains(projected.get("estado"))) throw new DeudasException(PROTOCOLO);
            if (!Set.of("CLP","UF").contains(projected.get("moneda"))) throw new DeudasException(PROTOCOLO);
            for (String key : List.of("montoOriginal", "saldo", "totalHoy", "interesMora", "tasaInteresMensual", "descuentoDisponible")) {
                Object value = debt.get(key);
                if (value == null) {
                    if (Set.of("montoOriginal","saldo","totalHoy","interesMora").contains(key)) throw new DeudasException(PROTOCOLO);
                } else {
                    if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue())) throw new DeudasException(PROTOCOLO);
                    projected.put(key, value);
                }
            }
            Object until = debt.get("descuentoHasta");
            if (until != null) {
                if (!(until instanceof String text)) throw new DeudasException(PROTOCOLO);
                try { java.time.LocalDate.parse(text); } catch (RuntimeException ex) { throw new DeudasException(PROTOCOLO); }
                projected.put("descuentoHasta", until);
            }
            result.add(Map.copyOf(projected));
        }
        return List.copyOf(result);
    }
}
