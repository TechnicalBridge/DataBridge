package com.tbridge.payments.config;

import com.tbridge.payments.security.JwtValidation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.web.client.RestTemplate;
import java.net.URI;
import java.time.Clock;

@Configuration
public class JwtConfig {
    @Bean
    JwtDecoder jwtDecoder(@Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String uri) {
        return remoteDecoder(uri,1000,2000);
    }

    public static JwtDecoder remoteDecoder(String location, int connectMillis, int readMillis) {
        URI uri=URI.create(location);
        if (!("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                || uri.getHost()==null || uri.getUserInfo()!=null || uri.getFragment()!=null) {
            throw new IllegalStateException("AUTH_JWKS_URI debe ser una URL HTTP(S) sin credenciales");
        }
        var factory=new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(connectMillis);
        factory.setReadTimeout(readMillis);
        var decoder=NimbusJwtDecoder.withJwkSetUri(uri.toString())
                .jwsAlgorithms(algorithms -> { algorithms.clear(); algorithms.add(SignatureAlgorithm.ES256); })
                .restOperations(new RestTemplate(factory)).build();
        return JwtValidation.secure(decoder,Clock.systemUTC());
    }
}
