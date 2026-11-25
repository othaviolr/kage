package com.kage.auth.domain.service;

import java.time.Instant;
import java.util.UUID;

/** Emite o access token (curto) de uma sessão. O refresh token é opaco e não passa por aqui. */
public interface TokenIssuer {

    IssuedToken issueForCustomer(UUID customerId, UUID sessionId);

    IssuedToken issueForAdmin(String username, UUID sessionId);

    record IssuedToken(String accessToken, Instant expiresAt) {}
}
