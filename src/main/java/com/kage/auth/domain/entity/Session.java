package com.kage.auth.domain.entity;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Uma sessão autenticada (um "dispositivo logado"). O access token carrega o id dela, e o filtro
 * de segurança confere a cada requisição se ela continua ativa: é isso que faz logout e bloqueio
 * valerem na hora, sem esperar o JWT expirar.
 *
 * Validade deslizante: cada refresh empurra o vencimento para {@link #IDLE_TIMEOUT} a partir de
 * agora — quem usa o app não é deslogado; quem some por 30 dias precisa entrar de novo.
 */
public class Session {

    public static final Duration IDLE_TIMEOUT = Duration.ofDays(30);

    public enum Role { CUSTOMER, ADMIN }

    private final UUID id;
    private final String subject;
    private final UUID customerId;
    private final Role role;
    private final String userAgent;
    private final LocalDateTime createdAt;
    private LocalDateTime lastRefreshedAt;
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
    private String revokeReason;

    private Session(UUID id, String subject, UUID customerId, Role role, String userAgent, LocalDateTime createdAt,
                    LocalDateTime lastRefreshedAt, LocalDateTime expiresAt, LocalDateTime revokedAt, String revokeReason) {
        this.id = id;
        this.subject = subject;
        this.customerId = customerId;
        this.role = role;
        this.userAgent = userAgent;
        this.createdAt = createdAt;
        this.lastRefreshedAt = lastRefreshedAt;
        this.expiresAt = expiresAt;
        this.revokedAt = revokedAt;
        this.revokeReason = revokeReason;
    }

    public static Session forCustomer(UUID customerId, String userAgent, LocalDateTime now) {
        return new Session(UUID.randomUUID(), customerId.toString(), customerId, Role.CUSTOMER, truncate(userAgent),
                now, now, now.plus(IDLE_TIMEOUT), null, null);
    }

    public static Session forAdmin(String username, String userAgent, LocalDateTime now) {
        return new Session(UUID.randomUUID(), username, null, Role.ADMIN, truncate(userAgent),
                now, now, now.plus(IDLE_TIMEOUT), null, null);
    }

    public static Session reconstitute(UUID id, String subject, UUID customerId, Role role, String userAgent,
                                       LocalDateTime createdAt, LocalDateTime lastRefreshedAt, LocalDateTime expiresAt,
                                       LocalDateTime revokedAt, String revokeReason) {
        return new Session(id, subject, customerId, role, userAgent, createdAt, lastRefreshedAt, expiresAt,
                revokedAt, revokeReason);
    }

    public boolean isActive(LocalDateTime now) {
        return revokedAt == null && now.isBefore(expiresAt);
    }

    public void refreshed(LocalDateTime now) {
        this.lastRefreshedAt = now;
        this.expiresAt = now.plus(IDLE_TIMEOUT);
    }

    public void revoke(String reason, LocalDateTime now) {
        if (revokedAt != null) return; // idempotente: mantém o primeiro motivo
        this.revokedAt = now;
        this.revokeReason = reason;
    }

    private static String truncate(String userAgent) {
        if (userAgent == null) return null;
        return userAgent.length() > 255 ? userAgent.substring(0, 255) : userAgent;
    }

    public UUID getId() { return id; }
    public String getSubject() { return subject; }
    public UUID getCustomerId() { return customerId; }
    public Role getRole() { return role; }
    public String getUserAgent() { return userAgent; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getLastRefreshedAt() { return lastRefreshedAt; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public LocalDateTime getRevokedAt() { return revokedAt; }
    public String getRevokeReason() { return revokeReason; }
}
