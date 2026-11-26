package com.kage.auth.domain.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Aparelhos confiáveis e desafios de login (código por SMS). */
public interface DeviceVerificationRepository {

    record TrustedDevice(UUID id, String userAgent, LocalDateTime trustedAt, LocalDateTime lastSeenAt) {}

    record Challenge(UUID id, UUID customerId, String codeHash, String deviceHash, String userAgent,
                     LocalDateTime createdAt, LocalDateTime expiresAt, int attempts, LocalDateTime usedAt) {}

    /** Se o aparelho é confiável para o cliente, registra o uso e devolve true. */
    boolean touchTrusted(UUID customerId, String deviceHash, LocalDateTime now);

    /** Passa a confiar no aparelho (idempotente: já confiável só atualiza o último uso). */
    void trust(UUID customerId, String deviceHash, String userAgent, LocalDateTime now);

    List<TrustedDevice> listTrusted(UUID customerId);

    /** @return false se o aparelho não existe, não é do cliente ou já foi esquecido */
    boolean revokeTrusted(UUID deviceId, UUID customerId, LocalDateTime now);

    int revokeAllTrusted(UUID customerId, LocalDateTime now);

    void insertChallenge(Challenge challenge);

    Optional<Challenge> findChallenge(UUID id);

    void registerFailedAttempt(UUID challengeId);

    /** @return false se outro uso simultâneo do mesmo desafio venceu */
    boolean markChallengeUsed(UUID challengeId, LocalDateTime now);

    int countChallengesSince(UUID customerId, LocalDateTime since);

    /** Pedir outro código invalida os anteriores. */
    void invalidatePendingChallenges(UUID customerId, LocalDateTime now);
}
