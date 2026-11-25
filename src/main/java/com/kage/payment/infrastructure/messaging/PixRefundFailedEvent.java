package com.kage.payment.infrastructure.messaging;

import java.util.UUID;

public record PixRefundFailedEvent(UUID refundId, String reason) {
}
