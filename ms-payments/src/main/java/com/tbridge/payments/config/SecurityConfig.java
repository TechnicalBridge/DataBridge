package com.tbridge.payments.config;

import com.tbridge.payments.security.JwtAuthFilter;
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
 * <p>El portal, con el JWT. La pasarela y su pagina de confirmacion no tienen
 * sesion: las protege la firma del pago. Lo interno, la clave interna. Sin
 * CORS: el navegador llega por el gateway, que es el que decide los origenes.
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
        //  Ademas, la vuelta de Webpay y los avisos de Khipu y Mercado Pago llegan
        //  desde otro sitio por diseno: los protege la verificacion con la pasarela.
        http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        //  Los protege la firma HMAC del pago, no una sesion.
                        .requestMatchers("/api/payments/webhooks/**").permitAll()
                        .requestMatchers("/api/payments/public/**").permitAll()
                        .requestMatchers("/internal/**").permitAll()
                        //  El panel de administracion es solo del admin de DataBridge.
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        //  Y todo lo demas, solo del deudor y de las empresas: el admin
                        //  no entra a un portal ajeno. Antes bastaba con tener sesion, y
                        //  quien no era empresa se trataba como deudor.
                        .anyRequest().hasAnyRole("DEBTOR", "CREDITOR")
                )
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req, res, e) -> {
                            res.setStatus(401);
                            res.setCharacterEncoding(StandardCharsets.UTF_8.name());
                            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            res.getWriter().write("{\"error\":\"No autorizado\"}");
                        })
                        //  Con sesion, pero de otro rol: un deudor o una empresa en el
                        //  panel de administracion, o el admin en un portal ajeno.
                        .accessDeniedHandler((req, res, e) -> {
                            res.setStatus(403);
                            res.setCharacterEncoding(StandardCharsets.UTF_8.name());
                            res.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            res.getWriter().write("{\"error\":\"No tienes permiso para esto\"}");
                        })
                )
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
