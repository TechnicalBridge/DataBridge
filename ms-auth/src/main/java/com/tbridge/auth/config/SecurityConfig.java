package com.tbridge.auth.config;

import com.tbridge.auth.security.JwtAuthFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.nio.charset.StandardCharsets;

/**
 * Quien puede llamar a que.
 *
 * <p>Sin sesion de servidor: cada peticion trae su JWT y {@link JwtAuthFilter}
 * lo valida. Sin CSRF porque ninguna ruta autentica con cookie salvo
 * {@code /api/auth/refresh} y {@code /api/auth/logout}, y esa cookie va con
 * {@code SameSite=Strict}: otro sitio no puede hacer que el navegador la mande.
 *
 * <p>Sin CORS: el navegador le habla al gateway por el mismo origen, y el
 * gateway es el que decide que origenes se aceptan.
 */
@Configuration
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        //  Sin CSRF, a proposito: la sesion viaja en el header Authorization, que
        //  el navegador no agrega solo cuando otro sitio dispara una peticion.
        //  La unica cookie, la llave de renovacion, es SameSite=Strict: otro sitio
        //  no puede hacer que el navegador la mande.
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        //  Donde se consigue la sesion: no puede exigirla.
                        .requestMatchers("/api/auth/**").permitAll()
                        //  El gateway no expone /internal: lo protege la clave interna.
                        .requestMatchers("/internal/**").permitAll()
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> {
                            res.setStatus(401);
                            res.setCharacterEncoding(StandardCharsets.UTF_8.name());
                            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            res.getWriter().write("{\"error\":\"No autorizado\"}");
                        })
                )
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
