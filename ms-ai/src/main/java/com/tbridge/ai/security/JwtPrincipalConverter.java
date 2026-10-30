package com.tbridge.ai.security;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import java.util.List;

/** Keeps the verified token request-local for independent verification by debt. */
public final class JwtPrincipalConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    @Override public AbstractAuthenticationToken convert(Jwt jwt) {
        JwtPrincipal principal = JwtPrincipal.fromClaims(jwt.getClaims());
        var authentication = new AbstractAuthenticationToken(List.of(new SimpleGrantedAuthority("ROLE_" + principal.role()))) {
            @Override public Object getPrincipal() { return principal; }
            @Override public Object getCredentials() { return jwt.getTokenValue(); }
        };
        authentication.setAuthenticated(true);
        return authentication;
    }
}
