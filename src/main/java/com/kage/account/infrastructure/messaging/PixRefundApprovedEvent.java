package com.kage.account.infrastructure.messaging;

import java.math.BigDecimal;
import java.util.UUID;

/** Devolver {@code amount} de quem recebeu o PIX original ({@code fromAccountId}) para quem pagou. */
public record PixRefundApprovedEvent(UUID refundId, UUID originalTransactionId, UUID fromAccountId,
                                     UUID toAccountId, BigDecimal amount) {
}
