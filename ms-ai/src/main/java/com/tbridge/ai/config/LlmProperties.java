package com.tbridge.ai.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import java.util.Set;

/** Only the selected provider is resolved. Never render secret-bearing config. */
@ConfigurationProperties(prefix = "app.llm")
public record LlmProperties(String provider, Endpoint groq, Endpoint gemini, Endpoint xai, Duration timeout) {
    public record Endpoint(String apiKey, String baseUrl, String model) {
        @Override public String toString() { return "Endpoint[redacted]"; }
    }
    public record Selected(String provider, String apiKey, String baseUrl, String model, Duration timeout) {
        public boolean configured() { return !provider.equals("local") && !apiKey.isBlank() && !model.isBlank(); }
        @Override public String toString() { return "Selected[redacted]"; }
    }
    public Selected resolve() {
        String name = provider == null ? "local" : provider.strip().toLowerCase(Locale.ROOT);
        if (!Set.of("local", "groq", "gemini", "xai").contains(name))
            throw new IllegalArgumentException("LLM_PROVIDER debe ser local, groq, gemini o xai");
        Duration limit = timeout == null ? Duration.ofSeconds(30) : timeout;
        if (limit.isZero() || limit.isNegative() || limit.compareTo(Duration.ofMinutes(2)) > 0)
            throw new IllegalArgumentException("LLM_TIMEOUT debe estar entre cero y dos minutos");
        if (name.equals("local")) return new Selected(name, "", "", "", limit);
        Endpoint endpoint = switch (name) { case "groq" -> groq; case "gemini" -> gemini; default -> xai; };
        String key = endpoint == null || endpoint.apiKey() == null ? "" : endpoint.apiKey().strip();
        String model = endpoint == null || endpoint.model() == null ? "" : endpoint.model().strip();
        // Provider/model are public metadata, so disallow URLs, whitespace and log control characters.
        if (!model.isEmpty() && !model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}"))
            throw new IllegalArgumentException("El modelo LLM tiene formato invalido");
        String url = endpoint == null || endpoint.baseUrl() == null ? "" : endpoint.baseUrl().strip().replaceAll("/+$", "");
        try {
            URI uri = URI.create(url);
            if (!Set.of("http", "https").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null)
                throw new IllegalArgumentException();
        } catch (RuntimeException invalid) { throw new IllegalArgumentException("La URL del proveedor LLM no es valida"); }
        return new Selected(name, key, url, model, limit);
    }
    @Override public String toString() { return "LlmProperties[redacted]"; }
}
