package com.kage.auth.application.usecase;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * O par de tokens que o app guarda: access token (JWT, 15 min, vai em toda requisição) e refresh
 * token (opaco, 30 dias, só serve para pedir um par novo em /api/auth/refresh).
 */
public record AuthTokens(String accessToken, String tokenType, Instant expiresAt,
                         String refreshToken, LocalDateTime refreshExpiresAt, UUID sessionId) {
}
