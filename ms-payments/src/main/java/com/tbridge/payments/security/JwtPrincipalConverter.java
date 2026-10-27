package com.tbridge.payments.security;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.*;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import java.util.*;

public final class JwtPrincipalConverter implements Converter<Jwt, AbstractAuthenticationToken> {
    @Override public AbstractAuthenticationToken convert(Jwt jwt) {
        JwtPrincipal principal=JwtPrincipal.fromClaims(jwt.getClaims());
        return UsernamePasswordAuthenticationToken.authenticated(principal,null,
                List.of(new SimpleGrantedAuthority("ROLE_"+principal.role().toUpperCase(Locale.ROOT))));
    }
}
