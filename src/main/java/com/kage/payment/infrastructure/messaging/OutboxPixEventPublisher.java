package com.kage.payment.infrastructure.messaging;

import com.kage.payment.application.usecase.PixEventPublisher;
import com.kage.payment.application.usecase.SendPix;
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
}
