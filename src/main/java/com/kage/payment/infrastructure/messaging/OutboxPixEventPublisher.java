package com.kage.payment.infrastructure.messaging;

import com.kage.payment.application.usecase.PixEventPublisher;
import com.kage.payment.application.usecase.SendPix;
import com.kage.payment.domain.entity.PixAutomaticAuthorization;
import com.kage.payment.domain.entity.PixAutomaticCharge;
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

    @Override
    public void publishPixAutomaticRequested(PixAutomaticAuthorization a) {
        outbox.publish("pix.exchange", "pix.automatic.requested", new PixAutomaticRequestedEvent(a.getId(),
                a.getPayerAccountId(), a.getReceiverName(), a.getDescription(), a.getFrequency().name(),
                a.getFixedAmount() == null ? null : a.getFixedAmount().amount(), a.getStartDate(), java.time.Instant.now()));
    }

    @Override
    public void publishPixAutomaticChargeScheduled(PixAutomaticAuthorization a, PixAutomaticCharge c, int attempt,
                                                   java.time.LocalDate scheduledDate) {
        outbox.publish("pix.exchange", "pix.automatic.charge.scheduled", new PixAutomaticChargeScheduledEvent(c.id(),
                a.getId(), a.getPayerAccountId(), a.getReceiverName(), a.getDescription(), c.amount().amount(), scheduledDate,
                attempt, java.time.Instant.now()));
    }

    public record PixAutomaticRequestedEvent(java.util.UUID authorizationId, java.util.UUID payerAccountId,
                                             String receiverName, String description, String frequency,
                                             java.math.BigDecimal fixedAmount, java.time.LocalDate startDate,
                                             java.time.Instant occurredAt) {}

    public record PixAutomaticChargeScheduledEvent(java.util.UUID chargeId, java.util.UUID authorizationId,
                                                   java.util.UUID payerAccountId, String receiverName, String description,
                                                   java.math.BigDecimal amount, java.time.LocalDate dueDate,
                                                   int attempt, java.time.Instant occurredAt) {}

    public record ScheduledPixFailedEvent(java.util.UUID transactionId, java.util.UUID sourceAccountId,
                                          java.math.BigDecimal amount, java.time.LocalDate scheduledDate,
                                          String reason, java.time.Instant occurredAt) {}
}
