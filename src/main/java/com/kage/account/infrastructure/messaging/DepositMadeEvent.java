package com.kage.account.infrastructure.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record DepositMadeEvent(UUID accountId, BigDecimal amount, BigDecimal newBalance, Instant occurredAt) {

}