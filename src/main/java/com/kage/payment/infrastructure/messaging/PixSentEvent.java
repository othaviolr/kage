package com.kage.payment.infrastructure.messaging;

import java.math.BigDecimal;
import java.util.UUID;

public record PixSentEvent(UUID transactionId, UUID sourceAccountId, String targetPixKey, UUID targetAccountId, BigDecimal amount, String status, String e2eId, String createdAt) {

}