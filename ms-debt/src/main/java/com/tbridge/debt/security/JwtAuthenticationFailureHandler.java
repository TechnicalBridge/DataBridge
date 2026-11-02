package com.tbridge.debt.security;

import jakarta.servlet.http.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.authentication.AuthenticationFailureHandler;

public final class JwtAuthenticationFailureHandler implements AuthenticationFailureHandler {
    @Override public void onAuthenticationFailure(HttpServletRequest request,HttpServletResponse response,
            AuthenticationException failure) throws IOException {
        boolean unavailable=failure instanceof AuthenticationServiceException;
        write(response,unavailable?503:401,unavailable?"Verificacion de sesion temporalmente no disponible":"Token invalido o caducado");
    }
    public static void write(HttpServletResponse response,int status,String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\""+message+"\"}");
    }
}
