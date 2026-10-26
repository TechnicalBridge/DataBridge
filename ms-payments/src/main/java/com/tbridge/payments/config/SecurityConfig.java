package com.tbridge.payments.config;

import com.tbridge.payments.security.*;
import org.springframework.context.annotation.*;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    private HttpSecurity base(HttpSecurity http) throws Exception {
        // Ambas cadenas son APIs stateless sin autenticacion por cookie ni sesion.
        // Portal: JWT en Authorization; /internal: X-Internal-Key; callbacks y webhooks:
        // firmas/enlaces/proveedores conservan su validacion propia, sin sesion de navegador.
        // CSRF no debe adelantarse a esas validaciones ni sustituir un 401 por 403.
        // Esta excepcion contextual no aplica a auth, que si usa refresh cookie.
        // Si se incorpora autenticacion por cookie aqui, reevaluar la proteccion CSRF.
        return http.csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req,res,e) -> JwtAuthenticationFailureHandler.write(res,401,"No autorizado"))
                        .accessDeniedHandler((req,res,e) -> JwtAuthenticationFailureHandler.write(res,403,"No tienes permiso para esto")));
    }

    @Bean @Order(1)
    public SecurityFilterChain sinSesionFilterChain(HttpSecurity http) throws Exception {
        // Conserva X-Internal-Key y firmas/validacion de pagos; no son sesiones.
        return base(http).securityMatcher("/internal/**", "/api/payments/public/**", "/api/payments/webhooks/**")
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll()).build();
    }

    @Bean @Order(2)
    public SecurityFilterChain portalFilterChain(HttpSecurity http, JwtDecoder decoder) throws Exception {
        base(http).authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .anyRequest().hasAnyRole("DEBTOR","CREDITOR"));
        http.oauth2ResourceServer(rs -> {
            rs.jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(new JwtPrincipalConverter()));
            rs.authenticationEntryPoint((req,res,e) -> JwtAuthenticationFailureHandler.write(res,401,"No autorizado"));
            rs.withObjectPostProcessor(new ObjectPostProcessor<BearerTokenAuthenticationFilter>() {
                @Override public <O extends BearerTokenAuthenticationFilter> O postProcess(O filter) {
                    filter.setAuthenticationFailureHandler(new JwtAuthenticationFailureHandler());
                    return filter;
                }
            });
        });
        return http.build();
    }
}
