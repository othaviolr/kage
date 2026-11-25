package com.kage.account.infrastructure.messaging;

import java.util.UUID;

public record PixRefundFailedEvent(UUID refundId, String reason) {
}
