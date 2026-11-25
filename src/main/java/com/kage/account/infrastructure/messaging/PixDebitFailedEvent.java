package com.kage.account.infrastructure.messaging;

import java.util.UUID;

public record PixDebitFailedEvent(UUID transactionId, String reason) {
}
