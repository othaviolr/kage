package com.kage.auth.domain.entity;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Credencial de acesso de um cliente. Guarda só o hash da senha (nunca a senha) e o controle de
 * força bruta: depois de {@link #MAX_FAILED_ATTEMPTS} erros seguidos, o login fica travado por
 * {@link #LOCK_DURATION}. Acertar a senha zera o contador.
 */
public class Credential {

    public static final int MAX_FAILED_ATTEMPTS = 5;
    public static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final UUID customerId;
    private String passwordHash;
    private int failedAttempts;
    private LocalDateTime lockedUntil;
    private final LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    private Credential(UUID customerId, String passwordHash, int failedAttempts, LocalDateTime lockedUntil,
                       LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.customerId = customerId;
        this.passwordHash = passwordHash;
        this.failedAttempts = failedAttempts;
        this.lockedUntil = lockedUntil;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static Credential create(UUID customerId, String passwordHash, LocalDateTime now) {
        return new Credential(customerId, passwordHash, 0, null, now, now);
    }

    public static Credential reconstitute(UUID customerId, String passwordHash, int failedAttempts,
                                          LocalDateTime lockedUntil, LocalDateTime createdAt, LocalDateTime updatedAt) {
        return new Credential(customerId, passwordHash, failedAttempts, lockedUntil, createdAt, updatedAt);
    }

    public boolean isLocked(LocalDateTime now) {
        return lockedUntil != null && now.isBefore(lockedUntil);
    }

    public void registerFailedAttempt(LocalDateTime now) {
        // trava expirada: a contagem recomeça
        if (lockedUntil != null && !now.isBefore(lockedUntil)) {
            failedAttempts = 0;
            lockedUntil = null;
        }
        failedAttempts++;
        if (failedAttempts >= MAX_FAILED_ATTEMPTS) {
            lockedUntil = now.plus(LOCK_DURATION);
        }
        updatedAt = now;
    }

    /** Senha nova (troca ou recuperação): também destrava, porque o dono acabou de provar quem é. */
    public void changePassword(String newPasswordHash, LocalDateTime now) {
        this.passwordHash = newPasswordHash;
        this.failedAttempts = 0;
        this.lockedUntil = null;
        this.updatedAt = now;
    }

    public void registerSuccessfulLogin(LocalDateTime now) {
        failedAttempts = 0;
        lockedUntil = null;
        updatedAt = now;
    }

    public UUID getCustomerId() { return customerId; }
    public String getPasswordHash() { return passwordHash; }
    public int getFailedAttempts() { return failedAttempts; }
    public LocalDateTime getLockedUntil() { return lockedUntil; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
