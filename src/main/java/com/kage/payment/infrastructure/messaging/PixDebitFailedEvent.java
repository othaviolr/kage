package com.kage.payment.infrastructure.messaging;

import java.util.UUID;

public record PixDebitFailedEvent(UUID transactionId, String reason) {
}
