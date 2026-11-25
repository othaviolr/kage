package com.kage.account.infrastructure.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Um PIX ou estorno que a conta tentou fazer foi recusado (routing keys account.pix.failed e
 * account.pix.refund.failed). {@code accountId} é quem precisa ser avisado: o pagador do PIX, ou
 * quem pediu o estorno.
 */
public record AccountPixFailedEvent(UUID accountId, UUID referenceId, BigDecimal amount, String reason, Instant occurredAt) {
}
