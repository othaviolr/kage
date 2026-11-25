package com.kage.auth.infrastructure.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Matriz de acesso, num lugar só:
 *
 * - público: cadastro, login, Swagger, health
 * - ADMIN: backoffice (KYC, bloqueios, estornos) e o que simula sistemas externos — entrada de
 *   dinheiro (depósito), compra no cartão (vinda da bandeira), fechamento de fatura (job do banco)
 * - qualquer autenticado: o resto. Aqui só se exige um token; QUE recurso o cliente pode tocar
 *   (o próprio) é checado pelo AccessGuard em cada controller.
 *
 * API stateless com Bearer token: sem sessão, sem cookie, então CSRF não se aplica.
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtService jwtService) throws Exception {
        SecurityErrorHandler errorHandler = new SecurityErrorHandler();

        http
                .csrf(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex.authenticationEntryPoint(errorHandler).accessDeniedHandler(errorHandler))
                .addFilterBefore(new JwtAuthenticationFilter(jwtService), UsernamePasswordAuthenticationFilter.class)
                .authorizeHttpRequests(auth -> auth
                        // público
                        .requestMatchers(HttpMethod.POST, "/api/auth/signup", "/api/auth/login", "/api/auth/admin/login").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**", "/api-docs/**", "/actuator/health").permitAll()
                        .requestMatchers("/error").permitAll()

                        // backoffice
                        .requestMatchers(HttpMethod.POST, "/api/customers").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/customers/*/kyc/**", "/api/customers/*/block", "/api/customers/*/unblock").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/accounts/*/block", "/api/accounts/*/unblock").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/pix/refunds/*/approve", "/api/pix/refunds/*/reject").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/cards").hasRole("ADMIN") // limite = análise de crédito

                        // simulação de sistemas externos
                        .requestMatchers(HttpMethod.POST, "/api/accounts/*/deposit").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/cards/*/purchases").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PATCH, "/api/cards/*/invoices/*/close").hasRole("ADMIN")

                        // demais rotas da API: autenticado + dono do recurso (AccessGuard)
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().denyAll()
                );
        return http.build();
    }
}
