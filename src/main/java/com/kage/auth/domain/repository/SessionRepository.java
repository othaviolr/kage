package com.kage.auth.domain.repository;

import com.kage.auth.domain.entity.Session;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SessionRepository {

    void insert(Session session);

    void update(Session session);

    Optional<Session> findById(UUID id);

    /** Consulta do filtro de segurança, a cada requisição: só diz se a sessão vale agora. */
    boolean isActive(UUID sessionId, LocalDateTime now);

    List<Session> findActiveByCustomer(UUID customerId, LocalDateTime now);

    /** @return quantas sessões foram encerradas */
    int revokeAllByCustomer(UUID customerId, String reason, LocalDateTime now);

    // --- refresh tokens (só o hash) ---

    void insertRefreshToken(String tokenHash, UUID sessionId, LocalDateTime issuedAt, LocalDateTime expiresAt);

    Optional<RefreshTokenRecord> findRefreshToken(String tokenHash);

    /**
     * Marca o token como usado só se ainda não foi (UPDATE ... WHERE used_at IS NULL): dois refresh
     * simultâneos com o mesmo token não rodam os dois — o segundo vê false.
     */
    boolean markRefreshTokenUsed(String tokenHash, LocalDateTime now);

    record RefreshTokenRecord(String tokenHash, UUID sessionId, LocalDateTime expiresAt, LocalDateTime usedAt) {}
}
