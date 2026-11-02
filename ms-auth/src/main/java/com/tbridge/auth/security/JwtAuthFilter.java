package com.tbridge.auth.security;

import org.springframework.security.oauth2.jwt.Jwt;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtService jwtService;

    public JwtAuthFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    /**
     * Rutas que no se autentican con la sesion del portal.
     *
     * <p>El contrato de integracion tambien usa {@code Authorization: Bearer},
     * pero con una clave de API, no con un JWT. Sin esta excepcion el filtro
     * intentaba leer la clave como token y respondia 401 antes de que el
     * controlador pudiera siquiera mirarla.
     *
     * <p>{@code /internal} va con clave interna y tampoco pasa por aqui.
     *
     * <p>{@code /api/auth/} es donde se consigue la sesion, asi que no puede
     * exigirla. Y hay un caso que lo hace obligatorio: renovar se pide justo
     * cuando el JWT vencio, y si el navegador lo manda igual en la cabecera,
     * este filtro respondia 401 antes de que el controlador alcanzara a mirar
     * la llave de renovacion. {@code /api/me} no esta bajo esa ruta y sigue
     * pidiendo el JWT.
     */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String ruta = request.getRequestURI();
        return ("GET".equals(request.getMethod()) && ruta.equals("/.well-known/jwks.json"))
                || ruta.startsWith("/api/v1/") || ruta.startsWith("/internal/")
                || ruta.startsWith("/api/auth/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }
        String raw = header.substring(7).trim();
        if (raw.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            Jwt claims = jwtService.parse(raw);
            JwtPrincipal principal = jwtService.toPrincipal(claims);
            //  Basta con id y ALGUNA identidad. Antes se exigia el correo,
            //  porque toda sesion venia de una cuenta; el deudor que entra con
            //  su codigo de acceso no tiene cuenta ni correo, se identifica
            //  por RUT, y con la regla anterior quedaba fuera del portal.
            boolean sinIdentidad = (principal.email() == null || principal.email().isBlank())
                    && (principal.rut() == null || principal.rut().isBlank());
            if (principal.id() == null || sinIdentidad) {
                write(response, HttpServletResponse.SC_UNAUTHORIZED, "Sesión inválida");
                return;
            }
            String role = principal.role() == null ? "DEBTOR" : principal.role().toUpperCase();
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    principal,
                    null,
                    List.of(new SimpleGrantedAuthority("ROLE_" + role))
            );
            authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (Exception e) {
            write(response, HttpServletResponse.SC_UNAUTHORIZED, "Token inválido o caducado");
        }
    }

    private void write(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
