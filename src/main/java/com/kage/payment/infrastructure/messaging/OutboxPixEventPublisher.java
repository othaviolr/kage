package com.kage.payment.infrastructure.messaging;

import com.kage.payment.application.usecase.PixEventPublisher;
import com.kage.payment.application.usecase.SendPix;
import com.kage.payment.domain.entity.PixRefund;
import com.kage.payment.domain.entity.PixTransaction;
import com.kage.shared.infrastructure.messaging.OutboxEventPublisher;

public class OutboxPixEventPublisher implements PixEventPublisher {

    private final OutboxEventPublisher outbox;

    public OutboxPixEventPublisher(OutboxEventPublisher outbox) {
        this.outbox = outbox;
    }

    @Override
    public void publishPixSent(SendPix.Output output) {
        PixSentEvent event = new PixSentEvent(output.transactionId(), output.sourceAccountId(),
                output.targetPixKey(), output.targetAccountId(), output.amount(), output.status(), output.e2eId(), output.createdAt());

        outbox.publish("pix.exchange", "pix.sent", event);
    }

    @Override
    public void publishRefundApproved(PixRefund refund, PixTransaction originalTransaction) {
        PixRefundApprovedEvent event = new PixRefundApprovedEvent(refund.getRefundId(),
                originalTransaction.getTransactionId(), originalTransaction.getTargetAccountId(),
                originalTransaction.getSourceAccountId(), refund.getRefundAmount().amount());

        outbox.publish("pix.exchange", "pix.refund.approved", event);
    }

    @Override
    public void publishScheduledPixFailed(PixTransaction transaction, String reason) {
        outbox.publish("pix.exchange", "pix.scheduled.failed", new ScheduledPixFailedEvent(transaction.getTransactionId(),
                transaction.getSourceAccountId(), transaction.getAmount().amount(),
                transaction.getScheduledDate().toLocalDate(), reason, java.time.Instant.now()));
    }

    public record ScheduledPixFailedEvent(java.util.UUID transactionId, java.util.UUID sourceAccountId,
                                          java.math.BigDecimal amount, java.time.LocalDate scheduledDate,
                                          String reason, java.time.Instant occurredAt) {}
}
