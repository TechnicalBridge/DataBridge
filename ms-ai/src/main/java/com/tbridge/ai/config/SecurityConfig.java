package com.tbridge.ai.config;

import com.tbridge.ai.security.*;
import org.springframework.context.annotation.*;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.ObjectPostProcessor;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain security(HttpSecurity http, JwtDecoder decoder) throws Exception {
        // Chat authenticates with an explicit Bearer, never with a browser cookie.
        http.csrf(csrf -> csrf.ignoringRequestMatchers(new org.springframework.security.web.util.matcher.AntPathRequestMatcher("/api/ai/chat", "POST")))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(AbstractHttpConfigurer::disable).formLogin(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/ai/salud", "/actuator/health/**", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/ai/chat").hasRole("DEBTOR")
                        .anyRequest().denyAll())
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint((req,res,e) -> JwtAuthenticationFailureHandler.write(res,401,"No autorizado"))
                        .accessDeniedHandler((req,res,e) -> JwtAuthenticationFailureHandler.write(res,403,"No tienes permiso para esto")));
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
