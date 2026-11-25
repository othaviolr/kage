package com.kage.auth.domain.service;

import java.time.Instant;
import java.util.UUID;

public interface TokenIssuer {

    IssuedToken issueForCustomer(UUID customerId);

    IssuedToken issueForAdmin(String username);

    record IssuedToken(String accessToken, Instant expiresAt) {}
}
