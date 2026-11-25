package com.kage.auth.application.usecase;

import com.kage.auth.domain.entity.Session;
import com.kage.auth.domain.repository.SessionRepository;
import com.kage.shared.domain.exception.NotFoundException;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * "Onde estou logado": listar sessões ativas, sair desta, sair de um dispositivo específico ou de
 * todos. Encerrar uma sessão derruba na hora o access token dela (o filtro consulta a sessão).
 */
public class ManageSessions {

    public record SessionView(UUID id, String userAgent, LocalDateTime createdAt, LocalDateTime lastActivityAt,
                              LocalDateTime expiresAt, boolean current) {}

    private final SessionRepository sessionRepository;
    private final Clock clock;

    public ManageSessions(SessionRepository sessionRepository, Clock clock) {
        this.sessionRepository = sessionRepository;
        this.clock = clock;
    }

    public List<SessionView> listActive(UUID customerId, UUID currentSessionId) {
        return sessionRepository.findActiveByCustomer(customerId, LocalDateTime.now(clock)).stream()
                .map(s -> new SessionView(s.getId(), s.getUserAgent(), s.getCreatedAt(), s.getLastRefreshedAt(),
                        s.getExpiresAt(), s.getId().equals(currentSessionId)))
                .toList();
    }

    public void logout(UUID sessionId) {
        revoke(sessionId, "LOGOUT");
    }

    /**
     * Encerra uma sessão do cliente (ex.: celular perdido). Sessão de outro cliente responde 404.
     *
     * @param customerId dono esperado; nulo para o ADMIN
     */
    public void revokeSession(UUID sessionId, UUID customerId) {
        Session session = sessionRepository.findById(sessionId)
                .filter(s -> customerId == null || customerId.equals(s.getCustomerId()))
                .orElseThrow(() -> new NotFoundException("Sessão não encontrada"));
        session.revoke("REVOKED_BY_USER", LocalDateTime.now(clock));
        sessionRepository.update(session);
    }

    /** @return quantas sessões foram encerradas */
    public int logoutAll(UUID customerId) {
        return sessionRepository.revokeAllByCustomer(customerId, "LOGOUT_ALL", LocalDateTime.now(clock));
    }

    /** Cliente bloqueado pelo backoffice: ninguém continua logado como ele. */
    public int revokeAllBecauseBlocked(UUID customerId) {
        return sessionRepository.revokeAllByCustomer(customerId, "CUSTOMER_BLOCKED", LocalDateTime.now(clock));
    }

    private void revoke(UUID sessionId, String reason) {
        sessionRepository.findById(sessionId).ifPresent(session -> {
            session.revoke(reason, LocalDateTime.now(clock));
            sessionRepository.update(session);
        });
    }
}
