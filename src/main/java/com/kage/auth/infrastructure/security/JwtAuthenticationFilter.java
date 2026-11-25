package com.kage.auth.infrastructure.security;

import com.kage.auth.domain.repository.SessionRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;

/**
 * Lê "Authorization: Bearer <jwt>" e, se o token for válido E a sessão dele continuar ativa,
 * autentica a requisição. Assinatura e expiração do JWT não bastam: um token de sessão encerrada
 * (logout, bloqueio, refresh token roubado) para de valer na hora, não em 15 minutos.
 *
 * Token ausente ou inválido não barra aqui: a requisição segue anônima e o SecurityConfig decide
 * (401 em rota protegida, livre em rota pública).
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final SessionRepository sessionRepository;
    private final Clock clock;

    public JwtAuthenticationFilter(JwtService jwtService, SessionRepository sessionRepository, Clock clock) {
        this.jwtService = jwtService;
        this.sessionRepository = sessionRepository;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(PREFIX)) {
            jwtService.parse(header.substring(PREFIX.length()).trim())
                    .filter(user -> sessionRepository.isActive(user.sessionId(), LocalDateTime.now(clock)))
                    .ifPresent(user -> {
                        var authentication = new UsernamePasswordAuthenticationToken(user, null,
                                List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
                        SecurityContextHolder.getContext().setAuthentication(authentication);
                    });
        }
        chain.doFilter(request, response);
    }
}
