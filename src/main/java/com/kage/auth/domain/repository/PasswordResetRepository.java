package com.kage.auth.domain.repository;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

/** Tokens de recuperação de senha. Só o SHA-256 é guardado, como no refresh token. */
public interface PasswordResetRepository {

    void insert(String tokenHash, UUID customerId, LocalDateTime createdAt, LocalDateTime expiresAt);

    Optional<ResetToken> findByHash(String tokenHash);

    /** UPDATE ... WHERE used_at IS NULL: dois usos simultâneos do mesmo link não passam os dois. */
    boolean markUsedIfUnused(String tokenHash, LocalDateTime now);

    /** Pedir um link novo invalida os anteriores que ainda estavam pendentes. */
    void invalidatePending(UUID customerId, LocalDateTime now);

    int countCreatedSince(UUID customerId, LocalDateTime since);

    record ResetToken(String tokenHash, UUID customerId, LocalDateTime expiresAt, LocalDateTime usedAt) {}
}
